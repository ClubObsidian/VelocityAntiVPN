package me.egg82.antivpn.storage;

import me.egg82.antivpn.core.IPResult;
import me.egg82.antivpn.core.PlayerResult;
import me.egg82.antivpn.core.PostVPNResult;
import me.egg82.antivpn.core.RawVPNResult;
import me.egg82.antivpn.core.VPNResult;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface Storage {
    void close();

    boolean isClosed();

    Set<VPNResult> getVPNQueue() throws StorageException;

    VPNResult getVPNByIP(String ip, long cacheTimeMillis) throws StorageException;

    default PostVPNResult postVPN(String ip, boolean cascade) throws StorageException {
        return postVPN(ip, Optional.of(cascade), Optional.empty());
    }

    default PostVPNResult postVPN(String ip, double consensus) throws StorageException {
        return postVPN(ip, Optional.empty(), Optional.of(consensus));
    }

    PostVPNResult postVPN(String ip, Optional<Boolean> cascade, Optional<Double> consensus) throws StorageException;

    void setIPRaw(long longIPID, String ip) throws StorageException;

    void setPlayerRaw(long longPlayerID, UUID playerID) throws StorageException;

    void postVPNRaw(long id, long longIPID, Optional<Boolean> cascade, Optional<Double> consensus, long created) throws StorageException;

    long getLongPlayerID(UUID playerID);

    long getLongIPID(String ip);

    Set<IPResult> dumpIPs(long begin, int size) throws StorageException;

    void loadIPs(Set<IPResult> ips, boolean truncate) throws StorageException;

    Set<PlayerResult> dumpPlayers(long begin, int size) throws StorageException;

    void loadPlayers(Set<PlayerResult> players, boolean truncate) throws StorageException;

    Set<RawVPNResult> dumpVPNValues(long begin, int size) throws StorageException;

    void loadVPNValues(Set<RawVPNResult> values, boolean truncate) throws StorageException;

}
