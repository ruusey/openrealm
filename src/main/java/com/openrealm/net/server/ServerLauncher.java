package com.openrealm.net.server;

import java.net.http.HttpClient;

import com.openrealm.account.dto.PingResponseDto;
import com.openrealm.account.service.OpenRealmServerDataService;
import com.openrealm.game.data.GameDataManager;
import com.openrealm.net.realm.RealmManagerServer;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerLauncher {

    public static void main(String[] args) {
        ServerLauncher.log.info("Starting OpenRealm Server {}...", ServerGameLogic.GAME_VERSION);

        // Pick the first arg that looks like a data-service address (URL or
        // IP/hostname). Skips legacy flag-style args (e.g. "-server") that
        // may still be in deployed systemd units after the launcher stopped
        // using them — without this, an old unit-file would feed "-server"
        // as args[0], producing "http://-server/ping" and a startup crash.
        String addr = null;
        for (final String a : args) {
            if (a == null || a.isEmpty() || a.startsWith("-")) continue;
            addr = a;
            break;
        }
        if (addr == null) {
            ServerLauncher.log.info("NO USABLE ADDR ARG. Assuming local data service is running at 127.0.0.1");
            addr = "127.0.0.1";
        }

        final String dataServiceUrl;
        if (addr.startsWith("http://")) {
            dataServiceUrl = addr.endsWith("/") ? addr : addr + "/";
        } else {
            dataServiceUrl = "http://" + addr + "/";
        }

        ServerGameLogic.DATA_SERVICE = new OpenRealmServerDataService(HttpClient.newHttpClient(), dataServiceUrl);

        ServerLauncher.pingServer();
        GameDataManager.loadGameData(true);

        final RealmManagerServer server = new RealmManagerServer();
        server.doRunServer();
    }

    private static void pingServer() {
        try {
            PingResponseDto dataServerOnline = ServerGameLogic.DATA_SERVICE.executeGet("ping", null,
                    PingResponseDto.class);
            ServerLauncher.log.info("Data server online. Response: {}", dataServerOnline);
        } catch (Exception e) {
            ServerLauncher.log.error("FATAL. Unable to reach data server at {}. Reason: {}",
                    ServerGameLogic.DATA_SERVICE.getBaseUrl(), e.getMessage());
            System.exit(-1);
        }
    }
}
