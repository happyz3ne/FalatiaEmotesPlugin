package org.falatia.emotes.plugin;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.falatia.emotes.plugin.net.PacketReader;
import org.falatia.emotes.plugin.net.PacketWriter;

public final class EmoteRelay implements Listener, PluginMessageListener {

    public static final String CHANNEL = "falatia:emote";
    private static final int PROTOCOL = 1;

    private static final int PLAY = 0;
    private static final int STOP = 1;
    private static final int DATA = 2;
    private static final int REQUEST = 3;
    private static final int INVITE = 4;
    private static final int JOIN = 5;
    private static final int PAIRED = 6;
    private static final int CATALOG = 7;
    private static final int FILE = 8;

    private static final int MAX_ID = 256;
    private static final int MAX_DATA = 32767;
    private static final int PACKETS_PER_SECOND = 40;
    private static final double PAIR_DISTANCE = 2.5;
    private static final double ANCHOR_SLACK = 3.0;

    private static final class Active {
        final String id;
        final long hash;
        final int startTick;
        final boolean invite;
        final double x;
        final double y;
        final double z;
        final float yaw;
        UUID partner;
        int joinTick;

        Active(String id, long hash, int startTick, boolean invite, double x, double y, double z, float yaw) {
            this.id = id;
            this.hash = hash;
            this.startTick = startTick;
            this.invite = invite;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }
    }

    private final FalatiaEmotesPlugin plugin;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, UUID> partnerOf = new HashMap<>();
    private final LinkedHashMap<Long, byte[]> files = new LinkedHashMap<>(16, 0.75F, true);
    private long filesBytes;
    private final Map<UUID, Integer> packets = new HashMap<>();
    private final Map<UUID, Integer> uploads = new HashMap<>();
    private final ServerEmotes serverEmotes;
    private final Set<UUID> joined = new HashSet<>();

    private boolean enabled;
    private long cacheLimit;
    private int uploadsPerMinute;

    public EmoteRelay(FalatiaEmotesPlugin plugin) {
        this.plugin = plugin;
        this.serverEmotes = new ServerEmotes(plugin);
    }

    public void enable() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(this.plugin, CHANNEL);
        messenger.registerIncomingPluginChannel(this.plugin, CHANNEL, this);
        Bukkit.getPluginManager().registerEvents(this, this.plugin);
        Bukkit.getScheduler().runTaskTimer(this.plugin, this.packets::clear, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this.plugin, this.uploads::clear, 1200L, 1200L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            this.joined.add(player.getUniqueId());
        }
        reload();
    }

    public void reload() {
        this.enabled = this.plugin.getConfig().getBoolean("enabled", true);
        this.cacheLimit = Math.max(1, this.plugin.getConfig().getLong("cache-mb", 16)) * 1024L * 1024L;
        this.uploadsPerMinute = Math.max(1, this.plugin.getConfig().getInt("uploads-per-minute", 20));
        if (!this.enabled) {
            this.active.clear();
            this.partnerOf.clear();
            this.files.clear();
            this.filesBytes = 0;
        }
        trim();
        this.serverEmotes.load();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (this.joined.contains(player.getUniqueId())) {
                sendCatalog(player);
            }
        }
    }

    private void sendCatalog(Player player) {
        if (!player.isOnline() || !player.getListeningPluginChannels().contains(CHANNEL)) {
            return;
        }
        List<ServerEmotes.EmoteFile> files = this.enabled ? this.serverEmotes.files() : List.of();
        send(player, new PacketWriter().varInt(PROTOCOL).varInt(CATALOG).varInt(files.size()));
        for (ServerEmotes.EmoteFile file : files) {
            send(player, new PacketWriter().varInt(PROTOCOL).varInt(FILE).string(file.path())
                    .byteArray(file.packed(), 0, file.packed().length));
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) || !this.enabled
                || this.packets.merge(player.getUniqueId(), 1, Integer::sum) > PACKETS_PER_SECOND) {
            return;
        }
        try {
            PacketReader reader = new PacketReader(message);
            if (reader.varInt() != PROTOCOL) {
                return;
            }
            switch (reader.varInt()) {
                case PLAY -> play(player, reader.string(MAX_ID), reader.longValue());
                case INVITE -> invite(player, reader.string(MAX_ID), reader.longValue(),
                        reader.doubleValue(), reader.doubleValue(), reader.doubleValue(), reader.floatValue());
                case JOIN -> join(player, reader.uuid());
                case STOP -> stop(player);
                case DATA -> store(player, reader.longValue(), reader.byteArray(MAX_DATA));
                case REQUEST -> {
                    long hash = reader.longValue();
                    byte[] data = this.files.get(hash);
                    if (data != null) {
                        send(player, new PacketWriter().varInt(PROTOCOL).varInt(DATA).longValue(hash).byteArray(data, 0, data.length));
                    }
                }
                default -> {
                }
            }
        } catch (IllegalArgumentException e) {
        }
    }

    private void play(Player player, String id, long hash) {
        leavePair(player);
        Location at = player.getLocation();
        Active emote = new Active(id, hash, Bukkit.getCurrentTick(), false, at.getX(), at.getY(), at.getZ(), at.getYaw());
        this.active.put(player.getUniqueId(), emote);
        for (Player viewer : player.getTrackedBy()) {
            sendState(viewer, player);
        }
    }

    private void invite(Player player, String id, long hash, double x, double y, double z, float yaw) {
        leavePair(player);
        Location at = player.getLocation();
        boolean near = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(yaw)
                && at.distanceSquared(new Location(at.getWorld(), x, y, z)) <= ANCHOR_SLACK * ANCHOR_SLACK;
        Active emote = near
                ? new Active(id, hash, Bukkit.getCurrentTick(), true, x, y, z, yaw)
                : new Active(id, hash, Bukkit.getCurrentTick(), true, at.getX(), at.getY(), at.getZ(), at.getYaw());
        this.active.put(player.getUniqueId(), emote);
        for (Player viewer : player.getTrackedBy()) {
            sendState(viewer, player);
        }
    }

    private void join(Player joiner, UUID leaderId) {
        Player leader = Bukkit.getPlayer(leaderId);
        Active emote = this.active.get(leaderId);
        if (leader == null || leader == joiner || emote == null || !emote.invite || emote.partner != null
                || leader.getWorld() != joiner.getWorld()
                || joiner.getLocation().distanceSquared(leader.getLocation()) > PAIR_DISTANCE * PAIR_DISTANCE
                || !joiner.hasLineOfSight(leader)) {
            return;
        }
        stop(joiner);
        emote.partner = joiner.getUniqueId();
        emote.joinTick = Bukkit.getCurrentTick();
        this.partnerOf.put(joiner.getUniqueId(), leaderId);
        for (Player viewer : pairAudience(leader, joiner)) {
            sendPaired(viewer, leader, emote);
        }
    }

    private void stop(Player player) {
        UUID id = player.getUniqueId();
        UUID leader = this.partnerOf.get(id);
        Active own = this.active.get(id);
        if (leader != null) {
            endPair(leader);
        } else if (own != null && own.partner != null) {
            endPair(id);
        } else if (this.active.remove(id) != null) {
            PacketWriter packet = stopPacket(id);
            for (Player viewer : player.getTrackedBy()) {
                if (sees(viewer, player)) {
                    send(viewer, packet);
                }
            }
        }
    }

    private void leavePair(Player player) {
        UUID leader = this.partnerOf.get(player.getUniqueId());
        Active own = this.active.get(player.getUniqueId());
        if (leader != null || own != null && own.partner != null) {
            stop(player);
        }
    }

    private void endPair(UUID leaderId) {
        Active emote = this.active.remove(leaderId);
        if (emote == null || emote.partner == null) {
            return;
        }
        this.partnerOf.remove(emote.partner);
        Player leader = Bukkit.getPlayer(leaderId);
        Player partner = Bukkit.getPlayer(emote.partner);
        PacketWriter leaderStop = stopPacket(leaderId);
        PacketWriter partnerStop = stopPacket(emote.partner);
        for (Player viewer : pairAudience(leader, partner)) {
            if (viewer != leader) {
                send(viewer, leaderStop);
            }
            if (viewer != partner) {
                send(viewer, partnerStop);
            }
        }
    }

    private Set<Player> pairAudience(Player leader, Player partner) {
        Set<Player> audience = new LinkedHashSet<>();
        for (Player member : new Player[] {leader, partner}) {
            if (member == null) {
                continue;
            }
            if (member.isOnline() && member.getListeningPluginChannels().contains(CHANNEL)) {
                audience.add(member);
            }
            for (Player viewer : member.getTrackedBy()) {
                if (sees(viewer, member)) {
                    audience.add(viewer);
                }
            }
        }
        return audience;
    }

    private static PacketWriter stopPacket(UUID player) {
        return new PacketWriter().varInt(PROTOCOL).varInt(STOP).uuid(player);
    }

    private void store(Player player, long hash, byte[] data) {
        if (this.files.containsKey(hash)
                || this.uploads.merge(player.getUniqueId(), 1, Integer::sum) > this.uploadsPerMinute) {
            return;
        }
        this.files.put(hash, data);
        this.filesBytes += data.length;
        trim();
    }

    private void trim() {
        Iterator<byte[]> iterator = this.files.values().iterator();
        while (this.filesBytes > this.cacheLimit && iterator.hasNext()) {
            this.filesBytes -= iterator.next().length;
            iterator.remove();
        }
    }

    private void sendState(Player viewer, Player player) {
        if (!sees(viewer, player)) {
            return;
        }
        UUID leaderId = this.partnerOf.get(player.getUniqueId());
        if (leaderId != null) {
            Player leader = Bukkit.getPlayer(leaderId);
            Active emote = this.active.get(leaderId);
            if (leader != null && emote != null) {
                sendPaired(viewer, leader, emote);
            }
            return;
        }
        Active emote = this.active.get(player.getUniqueId());
        if (emote == null) {
            return;
        }
        if (emote.partner != null) {
            sendPaired(viewer, player, emote);
            return;
        }
        int elapsed = Math.max(0, Bukkit.getCurrentTick() - emote.startTick);
        PacketWriter packet = new PacketWriter().varInt(PROTOCOL).varInt(emote.invite ? INVITE : PLAY).uuid(player.getUniqueId())
                .string(emote.id).longValue(emote.hash).varInt(elapsed);
        if (emote.invite) {
            packet.doubleValue(emote.x).doubleValue(emote.y).doubleValue(emote.z).floatValue(emote.yaw);
        }
        send(viewer, packet);
    }

    private void sendPaired(Player viewer, Player leader, Active emote) {
        int elapsed = Math.max(0, Bukkit.getCurrentTick() - emote.joinTick);
        send(viewer, new PacketWriter().varInt(PROTOCOL).varInt(PAIRED).uuid(leader.getUniqueId()).uuid(emote.partner)
                .string(emote.id).longValue(emote.hash).varInt(elapsed)
                .doubleValue(emote.x).doubleValue(emote.y).doubleValue(emote.z).floatValue(emote.yaw));
    }

    private boolean sees(Player viewer, Player player) {
        return viewer != player && viewer.isOnline() && viewer.canSee(player)
                && viewer.getListeningPluginChannels().contains(CHANNEL);
    }

    private void send(Player viewer, PacketWriter packet) {
        viewer.sendPluginMessage(this.plugin, CHANNEL, packet.bytes());
    }

    @EventHandler
    public void onTrack(PlayerTrackEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !busy(player.getUniqueId())) {
            return;
        }
        Player viewer = event.getPlayer();
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (busy(player.getUniqueId()) && player.getTrackedBy().contains(viewer)) {
                sendState(viewer, player);
            }
        });
    }

    private boolean busy(UUID player) {
        return this.active.containsKey(player) || this.partnerOf.containsKey(player);
    }

    @EventHandler
    public void onRegister(PlayerRegisterChannelEvent event) {
        if (!CHANNEL.equals(event.getChannel())) {
            return;
        }
        Player viewer = event.getPlayer();
        if (this.joined.contains(viewer.getUniqueId())) {
            sendCatalog(viewer);
        }
        Set<UUID> players = new LinkedHashSet<>(this.active.keySet());
        players.addAll(this.partnerOf.keySet());
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.getTrackedBy().contains(viewer)) {
                sendState(viewer, player);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        this.joined.add(player.getUniqueId());
        Bukkit.getScheduler().runTask(this.plugin, () -> sendCatalog(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.joined.remove(event.getPlayer().getUniqueId());
        stop(event.getPlayer());
        this.packets.remove(event.getPlayer().getUniqueId());
        this.uploads.remove(event.getPlayer().getUniqueId());
    }
}
