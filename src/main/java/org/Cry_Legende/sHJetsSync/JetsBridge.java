package org.Cry_Legende.sHJetsSync;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

/**
 * Accès à JetsAntiAFKPro 3.8.x, sans le modifier : le plugin n'a pas de dépôt public, il est lu par réflexion.
 *
 * <ul>
 *   <li>{@code PlayerController.onlinePlayers} (champ {@code playerController} de {@code AntiAFKProAPI}) : la table des
 *       fiches {@code AFKPlayer} (temps de jeu en secondes, -1 si sa lecture en base a échoué), pour relever le temps
 *       d'un joueur qui part, retirer une fiche périmée et remplacer une fiche à -1 ;</li>
 *   <li>{@code Properties} : base MySQL de JetsAntiAFKPro ({@code database.mysql} de son config.yml).</li>
 * </ul>
 *
 * <p>La table des fiches est une HashMap que JetsAntiAFKPro modifie lui-même depuis ses fils asynchrones : elle n'est
 * touchée ici que sur le fil principal, une entrée à la fois.</p>
 */
final class JetsBridge {

    private final Method getSecondsPlayed;
    private final Method getPlayer;
    private final Constructor<?> newAFKPlayer;
    private final Map<UUID, Object> players;

    final boolean storesPlaytime;
    final boolean mysql;
    final String host;
    final String port;
    final String database;
    final String user;
    final String password;
    final boolean ssl;

    @SuppressWarnings("unchecked")
    private JetsBridge(Plugin jets) throws ReflectiveOperationException {
        ClassLoader loader = jets.getClass().getClassLoader();
        Object api = jets.getClass().getMethod("getAntiAFKProAPI").invoke(jets);
        if (api == null) {
            throw new IllegalStateException("AntiAFKProAPI absente");
        }

        Class<?> afkPlayer = Class.forName("me.jet315.antiafkpro.manager.AFKPlayer", false, loader);
        this.getSecondsPlayed = afkPlayer.getMethod("getSecondsPlayed");
        this.getPlayer = afkPlayer.getMethod("getPlayer");
        this.newAFKPlayer = afkPlayer.getConstructor(Player.class, UUID.class, int.class);

        Field controllerField = api.getClass().getDeclaredField("playerController");
        controllerField.setAccessible(true);
        Object controller = controllerField.get(api);
        this.players = (Map<UUID, Object>) controller.getClass().getMethod("getOnlinePlayers").invoke(controller);

        Object properties = jets.getClass().getMethod("getProperties").invoke(jets);
        Class<?> p = properties.getClass();
        this.storesPlaytime = (Boolean) p.getMethod("isStorePlayerTime").invoke(properties);
        this.mysql = "MYSQL".equals(String.valueOf(p.getMethod("getDatabaseType").invoke(properties)));
        this.host = (String) p.getMethod("getMySQLHost").invoke(properties);
        this.port = (String) p.getMethod("getMySQLPort").invoke(properties);
        this.database = (String) p.getMethod("getMySQLDB").invoke(properties);
        this.user = (String) p.getMethod("getMySQLUser").invoke(properties);
        this.password = (String) p.getMethod("getMySQLPassword").invoke(properties);
        this.ssl = (Boolean) p.getMethod("isMySQLSSL").invoke(properties);
    }

    static JetsBridge open(Plugin jets) throws ReflectiveOperationException {
        return new JetsBridge(jets);
    }

    /**
     * Fiche du joueur dans la table, ou null (pas encore lue, ou déjà retirée à son départ). Elle peut appartenir à
     * une session terminée : voir {@link #owner(Object)}.
     */
    Object sheet(UUID uuid) {
        return players.get(uuid);
    }

    int seconds(Object sheet) {
        try {
            return (Integer) getSecondsPlayed.invoke(sheet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Joueur de la session pour laquelle la fiche a été créée. */
    Player owner(Object sheet) {
        try {
            return (Player) getPlayer.invoke(sheet);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Retire la fiche si c'est toujours celle-là. */
    boolean remove(UUID uuid, Object sheet) {
        return players.remove(uuid, sheet);
    }

    /** Remplace la fiche par une neuve avec ce temps, si c'est toujours celle-là. */
    boolean replace(Player player, Object sheet, int seconds) {
        try {
            return players.replace(player.getUniqueId(), sheet, newAFKPlayer.newInstance(player, player.getUniqueId(), seconds));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
