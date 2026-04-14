package com.conch.minecraftadmin.client;

import com.conch.minecraftadmin.model.McServerStatus;
import com.conch.minecraftadmin.model.ServerProfile;
import com.conch.minecraftadmin.model.ServerState;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ServerPollerTest {

    @Test
    void tickOnce_deliversStateFromBothSides() throws Exception {
        try (FakeAmpServer amp = new FakeAmpServer();
             FakeRconServer rcon = new FakeRconServer("rpw")) {

            amp.handle("Core/Login", body -> {
                JsonObject r = new JsonObject();
                r.addProperty("success", true);
                r.addProperty("sessionID", "abc");
                return r;
            });
            amp.handle("Core/GetInstances", body -> {
                JsonObject group = new JsonObject();
                JsonArray available = new JsonArray();
                JsonObject inst = new JsonObject();
                inst.addProperty("FriendlyName", "survival");
                inst.addProperty("InstanceName", "survival");
                inst.addProperty("Running", true);
                inst.addProperty("AppState", 30);
                inst.addProperty("TimeStarted", Instant.now().minusSeconds(120).toString());
                available.add(inst);
                group.add("AvailableInstances", available);
                JsonArray result = new JsonArray();
                result.add(group);
                JsonObject wrapper = new JsonObject();
                wrapper.add("result", result);
                return wrapper;
            });
            rcon.onCommand("list", cmd -> "There are 2 of a max of 20 players online: alice, bob");
            rcon.onCommand("tps", cmd -> "§6TPS from last 1m, 5m, 15m: §a19.5, §a20.0, §a20.0");

            ServerProfile profile = ServerProfile.create(
                "Survival",
                amp.baseUrl(),
                "survival",
                "admin",
                UUID.randomUUID(),
                "127.0.0.1",
                rcon.port(),
                UUID.randomUUID());

            AmpClient ampClient = new AmpClient(baseUrl -> new AmpClient.LoginPair("admin", "pw".toCharArray()));
            RconClient rconClient = new RconClient();
            AtomicReference<ServerState> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            StateListener listener = new StateListener() {
                @Override public void onStateUpdate(ServerState state) { received.set(state); latch.countDown(); }
                @Override public void onConsoleLines(List<String> lines) {}
                @Override public void onCrashDetected(ServerState state) {}
            };
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

            ServerPoller poller = new ServerPoller(
                profile, ampClient, rconClient, listener, scheduler, Runnable::run,
                () -> "rpw".toCharArray());

            try {
                poller.tickOnce();
                assertTrue(latch.await(3, TimeUnit.SECONDS), "state update was not delivered");
                ServerState state = received.get();
                assertEquals(McServerStatus.RUNNING, state.status());
                assertEquals(2, state.playersOnline());
                assertEquals(20, state.playersMax());
                assertEquals(19.5, state.tps(), 0.001);
                assertTrue(state.isAmpHealthy());
                assertTrue(state.isRconHealthy());
            } finally {
                poller.stop();
                scheduler.shutdownNow();
            }
        }
    }
}
