package com.termlab.sysinfo.collect;

final class SysInfoScript {
    private SysInfoScript() {}

    static final String COMMAND = """
        uname_s="$(uname -s 2>/dev/null || echo unknown)"
        marker() { printf '\\n__SYSINFO_%s__\\n' "$1"; }
        marker OS; printf '%s\\n' "$uname_s"
        marker HOSTNAME; hostname 2>/dev/null || printf 'unknown\\n'
        marker KERNEL; uname -r 2>/dev/null || printf 'unknown\\n'
        marker ARCH; uname -m 2>/dev/null || printf 'unknown\\n'
        if [ "$uname_s" = "Linux" ]; then
          marker PROC_STAT; head -n 1 /proc/stat 2>/dev/null
          marker MEMINFO; cat /proc/meminfo 2>/dev/null
          marker LOADAVG; cat /proc/loadavg 2>/dev/null
          marker UPTIME; cat /proc/uptime 2>/dev/null
          marker DF; df -kP / 2>/dev/null
          marker DISK_IO; cat /proc/diskstats 2>/dev/null
          marker NET; cat /proc/net/dev 2>/dev/null
          marker PS; ps -eo pid=,user=,pcpu=,pmem=,rss=,vsz=,args= 2>/dev/null
        elif [ "$uname_s" = "Darwin" ]; then
          marker TOP; top -l 1 -n 0 2>/dev/null | head -n 20
          marker VM_STAT; vm_stat 2>/dev/null
          marker MEMSIZE; sysctl -n hw.memsize 2>/dev/null
          marker LOADAVG; sysctl -n vm.loadavg 2>/dev/null
          marker UPTIME; uptime 2>/dev/null
          marker DF; df -kP / 2>/dev/null
          marker DISK_IO; iostat -Id -K 2>/dev/null
          marker NET; netstat -ibn 2>/dev/null
          marker PS; ps ax -o pid=,user=,%cpu=,%mem=,rss=,vsz=,command= 2>/dev/null
        else
          marker UNSUPPORTED; printf '%s\\n' "$uname_s"
        fi
        """;
}
