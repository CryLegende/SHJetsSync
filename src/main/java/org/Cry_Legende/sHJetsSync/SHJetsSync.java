package org.Cry_Legende.sHJetsSync;

import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Partage le temps de jeu de JetsAntiAFKPro entre les serveurs Opale du réseau (voir config.yml).
 *
 * <p>JetsAntiAFKPro lit le temps d'un joueur 400 ms après son arrivée et l'écrit à son départ sans rien attendre :
 * le serveur d'arrivée pouvait lire avant l'écriture du serveur de départ et repartir de la dernière sauvegarde
 * automatique (jusqu'à 600 s plus tôt), puis l'écraser, et rejouer un palier de temps de jeu.</p>
 *
 * <p>La connexion n'attend pas le serveur de départ : il ne sauvegarde le joueur qu'une fois cette connexion terminée
 * (le proxy ferme l'ancienne connexion après), l'attendre bloquerait la connexion jusqu'à son expiration. La barrière
 * de passage est suivie sans attente depuis la pré-connexion ; une fois passée, le temps est relu en base et la fiche
 * de JetsAntiAFKPro est relevée si elle a été lue avant.</p>
 *
 * <p>Fil principal : la table des fiches de JetsAntiAFKPro, {@code leaving}. Fil {@code SHJetsSync-db} : la base.
 * Fils de SHNetwork : fin de la barrière de passage, qui renvoie aussitôt au fil principal.</p>
 */
public final class SHJetsSync extends JavaPlugin implements Listener {

    private static final int WRITE_ATTEMPTS = 3;
    private static final int CHECK_ATTEMPTS = 3;
    private static final long ARRIVAL_KEEP_MS = 120_000L;

    /** Barrière de passage d'un joueur qui se connecte, lancée à sa pré-connexion et reprise à son arrivée. */
    private record Arrival(CompletableFuture<Boolean> saved, long at) {
    }

    private JetsBridge jets;
    private PlaytimeDatabase database;
    private ExecutorService io;
    private int waitSeconds;
    private int checkTicks;

    /** Fiche de chaque joueur qui part, relevée avant que JetsAntiAFKPro ne la retire (fil principal). */
    private final Map<UUID, Object> leaving = new HashMap<>();
    /** Barrière de chaque joueur en cours de connexion. */
    private final Map<UUID, Arrival> arrivals = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Plugin plugin = getServer().getPluginManager().getPlugin("JetsAntiAFKPro");
        if (plugin == null || !plugin.isEnabled()) {
            getLogger().severe("JetsAntiAFKPro n'est pas activé : SHJetsSync s'arrête.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            jets = JetsBridge.open(plugin);
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().severe("JetsAntiAFKPro est illisible (" + e + ", version autre que 3.8 ?) : SHJetsSync s'arrête.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!jets.storesPlaytime || !jets.mysql) {
            getLogger().warning("JetsAntiAFKPro ne range pas le temps de jeu en MySQL (store_player_time, database.mysql.use) : "
                + "rien à partager, SHJetsSync s'arrête.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        waitSeconds = Math.max(1, getConfig().getInt("attente-passage-secondes", 10));
        checkTicks = Math.max(20, getConfig().getInt("verification-ticks", 100));
        database = new PlaytimeDatabase(jets.host, jets.port, jets.database, jets.user, jets.password, jets.ssl,
            Math.max(500, getConfig().getInt("base-de-donnees.delai-ms", 3000)));
        io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SHJetsSync-db");
            thread.setDaemon(true);
            return thread;
        });

        getServer().getPluginManager().registerEvents(this, this);
        SHNetworkAPI.get().registerSaveTask(getName(), this::saveOnDeparture);
        getLogger().info("Temps de jeu de JetsAntiAFKPro partagé avec les autres serveurs du réseau (base " + jets.database + ").");
    }

    @Override
    public void onDisable() {
        if (io != null) {
            io.shutdown();
            try {
                io.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (database != null) {
            database.close();
        }
    }

    // ------------------------------------------------------------------ arrivée

    /**
     * Fil de connexion de Paper : suit la barrière de passage sans l'attendre. Le serveur de départ n'écrit le temps
     * du joueur qu'une fois cette connexion terminée, une attente ici durerait jusqu'au délai maximal.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        long now = System.currentTimeMillis();
        arrivals.values().removeIf(arrival -> now - arrival.at() > ARRIVAL_KEEP_MS);
        String name = event.getName();
        CompletableFuture<Boolean> saved = SHNetworkAPI.get().awaitSaved(event.getUniqueId(), Duration.ofSeconds(waitSeconds));
        saved.whenComplete((ok, error) -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                getLogger().warning("Départ de " + name + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : JetsAntiAFKPro lira la base telle quelle.");
            }
        });
        arrivals.put(event.getUniqueId(), new Arrival(saved, now));
    }

    /**
     * Avant JetsAntiAFKPro (LOW) : une fiche laissée par une session précédente (joueur reparti avant la fin de sa
     * lecture) servirait jusqu'à la nouvelle lecture, et serait écrite telle quelle s'il repartait aussitôt.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Object stale = jets.sheet(id);
        if (stale != null && jets.owner(stale) != player && jets.remove(id, stale)) {
            getLogger().info("Fiche périmée de " + player.getName() + " retirée (" + jets.seconds(stale) + " s, session précédente).");
        }
        Arrival arrival = arrivals.remove(id);
        getServer().getScheduler().runTaskLater(this, () -> verify(player, 1, false), checkTicks);
        if (arrival != null && !arrival.saved().isDone()) {
            // le serveur de départ n'a pas fini : JetsAntiAFKPro peut lire avant lui, le temps est relu dès qu'il a fini
            arrival.saved().whenComplete((ok, error) -> {
                if (isEnabled()) {
                    getServer().getScheduler().runTask(this, () -> verify(player, 1, true));
                }
            });
        }
    }

    /**
     * Une lecture ratée de JetsAntiAFKPro (« FAILED TO QUERY DATABASE ») laisse la fiche à -1 : rien n'est compté ni
     * sauvé pour la session. Une lecture d'une session précédente qui finit après celle-ci laisse une fiche périmée.
     * Dans les deux cas, le temps est relu ici et la fiche remplacée.
     *
     * <p>{@code afterDeparture} : le serveur de départ vient d'écrire le temps du joueur. JetsAntiAFKPro a pu lire
     * avant : si sa fiche est en retard sur la base, elle est remplacée (jamais abaissée).</p>
     */
    private void verify(Player player, int attempt, boolean afterDeparture) {
        if (!player.isOnline()) {
            return;
        }
        Object sheet = jets.sheet(player.getUniqueId());
        if (sheet == null) {
            if (attempt < CHECK_ATTEMPTS) {
                getServer().getScheduler().runTaskLater(this, () -> verify(player, attempt + 1, afterDeparture), checkTicks);
            }
            return;
        }
        boolean stale = jets.owner(sheet) != player;
        boolean failed = !stale && jets.seconds(sheet) == -1;
        if (!stale && !failed && !afterDeparture) {
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        String reason = stale ? "une fiche périmée" : failed ? "une lecture ratée de JetsAntiAFKPro"
            : "une lecture faite avant le départ de l'autre serveur";
        io.execute(() -> {
            int seconds;
            try {
                seconds = database.read(id);
            } catch (SQLException e) {
                getLogger().warning("Temps de jeu de " + name + " illisible en base, la session ne sera pas comptée : " + e.getMessage());
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (!player.isOnline()) {
                    return;
                }
                boolean behind = !stale && !failed && jets.seconds(sheet) < seconds;
                if ((stale || failed || behind) && jets.replace(player, sheet, seconds)) {
                    getLogger().info("Temps de jeu de " + name + " relu après " + reason + " : " + seconds + " s.");
                }
            });
        });
    }

    // ------------------------------------------------------------------ départ

    /** Avant JetsAntiAFKPro (LOW), qui retire la fiche : on la garde pour la tâche de sauvegarde (MONITOR). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Object sheet = jets.sheet(player.getUniqueId());
        if (sheet != null && jets.owner(sheet) == player) {
            leaving.put(player.getUniqueId(), sheet);
        }
    }

    /**
     * Tâche de sauvegarde de SHNetwork : fil principal, pendant PlayerQuitEvent, ou à l'arrêt du serveur (fiche encore
     * en place). JetsAntiAFKPro écrit la même valeur de son côté sans l'attendre ; celle-ci est terminée avant que
     * SHNetwork annonce le joueur sauvegardé.
     */
    private CompletableFuture<Void> saveOnDeparture(Player player) {
        UUID id = player.getUniqueId();
        Object sheet = leaving.remove(id);
        if (sheet == null) {
            Object current = jets.sheet(id);
            if (current != null && jets.owner(current) == player) {
                sheet = current;
            }
        }
        if (sheet == null) {
            return CompletableFuture.completedFuture(null);
        }
        int seconds = jets.seconds(sheet);
        if (seconds < 0) {
            // jamais lu : JetsAntiAFKPro n'écrit rien non plus
            return CompletableFuture.completedFuture(null);
        }
        String name = player.getName();
        try {
            return CompletableFuture.runAsync(() -> write(id, name, seconds), io);
        } catch (RuntimeException stopped) {
            write(id, name, seconds);
            return CompletableFuture.completedFuture(null);
        }
    }

    /** Trois essais ; un échec final rend la sauvegarde incomplète pour SHNetwork. */
    private void write(UUID id, String name, int seconds) {
        for (int attempt = 1; attempt <= WRITE_ATTEMPTS; attempt++) {
            try {
                database.write(id, seconds);
                return;
            } catch (SQLException e) {
                if (attempt == WRITE_ATTEMPTS) {
                    getLogger().warning("Temps de jeu de " + name + " non écrit en base (" + seconds + " s) : " + e.getMessage());
                    throw new CompletionException(e);
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(interrupted);
                }
            }
        }
    }
}
