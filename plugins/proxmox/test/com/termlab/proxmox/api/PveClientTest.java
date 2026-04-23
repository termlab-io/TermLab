package com.termlab.proxmox.api;

import com.termlab.proxmox.credentials.PveApiToken;
import com.termlab.proxmox.model.PveAction;
import com.termlab.proxmox.model.PveCluster;
import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PveClientTest {
    @Test
    void listsGuestsWithApiTokenHeader() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.response = new PveHttpResponse(200, """
            {"data":[{"type":"qemu","vmid":100,"name":"app","node":"pve","status":"running"}]}
            """);
        PveClient client = client(transport);

        List<PveGuest> guests = client.listGuests();

        assertEquals(1, guests.size());
        assertEquals("PVEAPIToken=root@pam!termlab=secret", transport.requests.get(0).authorization());
        assertEquals("/api2/json/cluster/resources", transport.requests.get(0).uri().getPath());
        assertEquals("type=vm", transport.requests.get(0).uri().getQuery());
    }

    @Test
    void choosesQemuAndLxcActionPaths() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.response = new PveHttpResponse(200, "{\"data\":\"UPID:pve:123\"}");
        PveClient client = client(transport);

        client.action(guest(PveGuestType.QEMU), PveAction.START);
        client.action(guest(PveGuestType.LXC), PveAction.STOP);

        assertEquals("/api2/json/nodes/pve/qemu/42/status/start", transport.requests.get(0).uri().getPath());
        assertEquals("/api2/json/nodes/pve/lxc/42/status/stop", transport.requests.get(1).uri().getPath());
        assertEquals("POST", transport.requests.get(1).method());
    }

    @Test
    void fetchesTaskStatus() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.response = new PveHttpResponse(200, "{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}");

        assertTrue(client(transport).taskStatus("pve", "UPID:pve:123").successful());
        assertEquals("/api2/json/nodes/pve/tasks/UPID:pve:123/status", transport.requests.get(0).uri().getPath());
    }

    @Test
    void raisesApiErrorsAndCertificateErrors() {
        FakeTransport httpError = new FakeTransport();
        httpError.response = new PveHttpResponse(401, "permission denied");
        assertThrows(PveApiException.class, () -> client(httpError).listGuests());

        FakeTransport certError = new FakeTransport();
        certError.certificateException = new PveUnknownCertificateException("AA:BB");
        assertThrows(PveUnknownCertificateException.class, () -> client(certError).listGuests());
    }

    private static PveClient client(FakeTransport transport) {
        return new PveClient(
            new PveCluster(UUID.randomUUID(), "PVE", "https://pve.example:8006", UUID.randomUUID(), "AA:BB"),
            new PveApiToken("root@pam!termlab", "secret".toCharArray()),
            transport
        );
    }

    private static PveGuest guest(PveGuestType type) {
        return new PveGuest(42, "guest", "pve", type, com.termlab.proxmox.model.PveGuestStatus.RUNNING,
            0, 1, 0, 0, 0, 0, 0, null);
    }

    private static final class FakeTransport implements PveHttpTransport {
        final List<PveHttpRequest> requests = new ArrayList<>();
        PveHttpResponse response = new PveHttpResponse(200, "{\"data\":[]}");
        PveCertificateException certificateException;

        @Override
        public PveHttpResponse send(PveHttpRequest request, String trustedCertificateSha256)
            throws IOException, InterruptedException, PveCertificateException {
            requests.add(request);
            if (certificateException != null) throw certificateException;
            return response;
        }
    }
}
