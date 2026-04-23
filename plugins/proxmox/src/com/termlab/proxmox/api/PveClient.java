package com.termlab.proxmox.api;

import com.intellij.openapi.diagnostic.Logger;
import com.termlab.proxmox.credentials.PveApiToken;
import com.termlab.proxmox.model.PveAction;
import com.termlab.proxmox.model.PveCluster;
import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestDetails;
import com.termlab.proxmox.model.PveTask;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class PveClient {
    private static final Logger LOG = Logger.getInstance(PveClient.class);

    private final PveCluster cluster;
    private final PveApiToken token;
    private final PveHttpTransport transport;

    public PveClient(
        @NotNull PveCluster cluster,
        @NotNull PveApiToken token,
        @NotNull PveHttpTransport transport
    ) {
        this.cluster = cluster;
        this.token = token;
        this.transport = transport;
    }

    public @NotNull List<PveGuest> listGuests()
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        LOG.info("TermLab Proxmox: listing guests for cluster '" + cluster.label()
            + "' at " + cluster.endpoint());
        PveHttpResponse response = send("GET", "/api2/json/cluster/resources?type=vm", null);
        List<PveGuest> guests = PveParser.parseGuests(response.body());
        List<PveGuest> sorted = guests.stream()
            .sorted(Comparator.comparing(PveGuest::node, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PveGuest::vmid))
            .toList();
        LOG.info("TermLab Proxmox: parsed " + sorted.size() + " VM/LXC resources for cluster '"
            + cluster.label() + "'");
        return sorted;
    }

    public @NotNull PveGuestDetails details(@NotNull PveGuest guest)
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        String path = "/api2/json/nodes/" + encode(guest.node())
            + "/" + guest.type().apiName()
            + "/" + guest.vmid()
            + "/config";
        LOG.info("TermLab Proxmox: loading config for " + guest.type().apiName()
            + "/" + guest.vmid() + " on node '" + guest.node() + "'");
        PveHttpResponse response = send("GET", path, null);
        Map<String, String> config = PveParser.parseConfig(response.body());
        LOG.info("TermLab Proxmox: parsed " + config.size() + " config keys for "
            + guest.type().apiName() + "/" + guest.vmid());
        return new PveGuestDetails(guest, config);
    }

    public @NotNull String action(@NotNull PveGuest guest, @NotNull PveAction action)
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        String path = "/api2/json/nodes/" + encode(guest.node())
            + "/" + guest.type().apiName()
            + "/" + guest.vmid()
            + "/status/" + action.apiName();
        LOG.info("TermLab Proxmox: sending " + action.apiName() + " to "
            + guest.type().apiName() + "/" + guest.vmid() + " on node '" + guest.node() + "'");
        PveHttpResponse response = send("POST", path, "");
        String upid = PveParser.parseTaskUpid(response.body());
        LOG.info("TermLab Proxmox: action " + action.apiName() + " accepted with task " + upid);
        return upid;
    }

    public @NotNull PveTask taskStatus(@NotNull String node, @NotNull String upid)
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        String path = "/api2/json/nodes/" + encode(node) + "/tasks/" + encode(upid) + "/status";
        PveHttpResponse response = send("GET", path, null);
        PveTask task = PveParser.parseTask(node, upid, response.body());
        LOG.info("TermLab Proxmox: task " + upid + " status=" + task.status()
            + " exitStatus=" + task.exitStatus());
        return task;
    }

    private @NotNull PveHttpResponse send(@NotNull String method, @NotNull String path, String body)
        throws IOException, InterruptedException, PveApiException, PveCertificateException {
        URI uri = endpoint().resolve(path);
        LOG.info("TermLab Proxmox: API request " + method + " " + uri
            + " trustedCert=" + (cluster.trustedCertificateSha256() == null ? "none" : "pinned"));
        PveHttpResponse response = transport.send(
            new PveHttpRequest(method, uri, token.authorizationValue(), body),
            cluster.trustedCertificateSha256()
        );
        LOG.info("TermLab Proxmox: API response " + response.statusCode() + " from " + method
            + " " + uri + " bodyBytes=" + response.body().length());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOG.warn("TermLab Proxmox: API request failed with HTTP " + response.statusCode()
                + " for " + method + " " + uri + ": " + truncate(response.body()));
            throw new PveApiException(response.statusCode(), errorMessage(response));
        }
        return response;
    }

    private @NotNull URI endpoint() {
        String value = cluster.endpoint().trim();
        if (!value.endsWith("/")) value += "/";
        return URI.create(value);
    }

    private static @NotNull String encode(@NotNull String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static @NotNull String errorMessage(@NotNull PveHttpResponse response) {
        String body = response.body().trim();
        if (body.isEmpty()) return "Proxmox API returned HTTP " + response.statusCode();
        return "Proxmox API returned HTTP " + response.statusCode() + ": " + body;
    }

    private static @NotNull String truncate(@NotNull String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 500 ? oneLine : oneLine.substring(0, 500) + "...";
    }
}
