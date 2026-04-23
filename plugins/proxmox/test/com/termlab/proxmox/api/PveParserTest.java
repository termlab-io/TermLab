package com.termlab.proxmox.api;

import com.termlab.proxmox.model.PveGuest;
import com.termlab.proxmox.model.PveGuestStatus;
import com.termlab.proxmox.model.PveGuestType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PveParserTest {
    @Test
    void parsesVmAndLxcRows() throws Exception {
        String json = """
            {"data":[
              {"type":"qemu","vmid":100,"name":"app vm","node":"pve1","status":"running","cpu":0.125,"maxcpu":4,"mem":1073741824,"maxmem":4294967296,"disk":2147483648,"maxdisk":10737418240,"uptime":3661},
              {"type":"lxc","vmid":201,"name":"worker","node":"pve2","status":"stopped","cpu":0,"maxcpu":2},
              {"type":"node","node":"pve1"}
            ]}
            """;

        List<PveGuest> guests = PveParser.parseGuests(json);

        assertEquals(2, guests.size());
        assertEquals(PveGuestType.QEMU, guests.get(0).type());
        assertEquals(PveGuestStatus.RUNNING, guests.get(0).status());
        assertEquals(12.5, guests.get(0).cpuPercent(), 0.001);
        assertEquals("app vm", guests.get(0).name());
        assertEquals(PveGuestType.LXC, guests.get(1).type());
        assertEquals(PveGuestStatus.STOPPED, guests.get(1).status());
    }

    @Test
    void toleratesMissingOptionalFields() throws Exception {
        List<PveGuest> guests = PveParser.parseGuests("""
            {"data":[{"type":"qemu","vmid":7,"node":"pve"}]}
            """);

        assertEquals(1, guests.size());
        assertEquals("qemu/7", guests.get(0).name());
        assertEquals(0, guests.get(0).memoryBytes());
    }

    @Test
    void parsesConfigAndTask() throws Exception {
        Map<String, String> config = PveParser.parseConfig("""
            {"data":{"cores":4,"name":"db","serial0":"socket"}}
            """);
        assertEquals("4", config.get("cores"));
        assertEquals("UPID:pve:1", PveParser.parseTaskUpid("""
            {"data":"UPID:pve:1"}
            """));
        assertEquals("OK", PveParser.parseTask("pve", "UPID:pve:1", """
            {"data":{"status":"stopped","exitstatus":"OK"}}
            """).exitStatus());
    }

    @Test
    void reportsMalformedJson() {
        assertThrows(PveApiException.class, () -> PveParser.parseGuests("{bad"));
        assertThrows(PveApiException.class, () -> PveParser.parseGuests("{}"));
    }
}
