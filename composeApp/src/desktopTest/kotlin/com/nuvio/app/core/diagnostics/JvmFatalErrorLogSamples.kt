package com.nuvio.app.core.diagnostics

/** Trimmed fatal error logs in the layout HotSpot 17 and 21 write. */
internal object JvmFatalErrorLogSamples {
    val windowsAccessViolation = """
        |#
        |# A fatal error has been detected by the Java Runtime Environment:
        |#
        |#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffccb69d4c7, pid=54704, tid=34932
        |#
        |# JRE version: OpenJDK Runtime Environment Temurin-17.0.13+11 (17.0.13+11) (build 17.0.13+11)
        |# Java VM: OpenJDK 64-Bit Server VM Temurin-17.0.13+11 (17.0.13+11, mixed mode, sharing, tiered, compressed oops, compressed class ptrs, g1 gc, windows-amd64)
        |# Problematic frame:
        |# C  [libmpv-2.dll+0x1f2a3]
        |#
        |# No core dump will be written. Minidumps are not enabled by default on client versions of Windows
        |#
        |# If you would like to submit a bug report, please visit:
        |#   https://github.com/adoptium/adoptium-support/issues
        |# The crash happened outside the Java Virtual Machine in native code.
        |# See problematic frame for where to report the bug.
        |#
        |
        |---------------  S U M M A R Y ------------
        |
        |Command Line: -Djava.library.path=C:\Users\tester\AppData\Local\Nuvio com.nuvio.app.MainKt nuvio://secret-link
        |
        |Host: AMD Ryzen 7 5800X 8-Core Processor, 16 cores, 31G,  Windows 11 , 64 bit Build 26100 (10.0.26100.1)
        |Time: Sat Nov 21 19:02:18 2026 GMT Standard Time elapsed time: 812.386227 seconds (0d 0h 13m 32s)
        |
        |---------------  T H R E A D  ---------------
        |
        |Current thread (0x00000132fb6d5fa0):  JavaThread "AWT-EventQueue-0"        [_thread_in_native, id=34932, stack(0x0000001f1f800000,0x0000001f1f900000)]
        |
        |Stack: [0x0000001f1f800000,0x0000001f1f900000],  sp=0x0000001f1f8feeb0,  free space=1019k
        |Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
        |C  [libmpv-2.dll+0x1f2a3]
        |C  [player_bridge.dll+0x4b10]
        |C  0x00000132d733ce8b
        |
        |Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
        |j  com.nuvio.app.features.player.desktop.NativePlayerBridge.nativeSeek(JD)V+0
        |j  com.nuvio.app.features.player.desktop.DesktopPlayerController.seekTo(J)V+42
        |J 1234 c2 java.awt.EventDispatchThread.pumpOneEventForFilters(I)V java.desktop@17.0.13 (106 bytes) @ 0x0000013281a5b2c4 [0x0000013281a5b1a0+0x0000000000000124]
        |v  ~StubRoutines::call_stub
        |
        |siginfo: EXCEPTION_ACCESS_VIOLATION (0xc0000005), reading address 0x0000000000000010
        |
        |
        |Registers:
        |RAX=0x000000000000002a, RBX=0x00000132fb6d5fa0, RCX=0x0000000000000000, RDX=0x0000001f1f8fefc8
        |
        |---------------  P R O C E S S  ---------------
        |
        |Dynamic libraries:
        |0x00007ff66cff0000 - 0x00007ff66cffe000 	C:\Program Files\Nuvio\Nuvio.exe
        |0x00007ffd93fb0000 - 0x00007ffd9421c000 	C:\Windows\SYSTEM32\ntdll.dll
        |0x00007ffd81230000 - 0x00007ffd82100000 	C:\Program Files\Nuvio\app\resources\native\windows\libmpv-2.dll
        |0x00007ffd80010000 - 0x00007ffd80040000 	C:\Users\tester\AppData\Local\Temp\nuvio-native\player_bridge.dll
        |0x00007ffd7f000000 - 0x00007ffd7f900000 	C:\Windows\System32\DriverStore\FileRepository\nvmdi.inf_amd64\nvoglv64.dll
        |0x00007ffd93fb0000 - 0x00007ffd9421c000 	C:\Windows\SYSTEM32\ntdll.dll
        |
        |VM Arguments:
        |jvm_args: -Djava.library.path=C:\Users\tester\AppData\Local\Nuvio
        |java_command: com.nuvio.app.MainKt nuvio://secret-link
        |
        |Environment Variables:
        |PATH=C:\Users\tester\bin;C:\Windows\system32
        |USERNAME=tester
        |NUVIO_TOKEN=do-not-send
        |
        |---------------  S Y S T E M  ---------------
        |
        |OS:
        | Windows 11 , 64 bit Build 26100 (10.0.26100.1)
        |OS uptime: 3 days 4:22 hours
        |
        |CPU: total 16 (initial active 16) (8 cores per cpu, 2 threads per core) family 25 model 33 stepping 0, cx8, cmov, avx, avx2
        |Processor Information for the first 16 processors :
        |  Max Mhz: 3801, Current Mhz: 3801, Mhz Limit: 3801
        |
        |Memory: 4k page, system-wide physical 32700M (18594M free)
        |TotalPageFile size 37500M (AvailPageFile size 20450M)
        |
        |vm_info: OpenJDK 64-Bit Server VM (17.0.13+11) for windows-amd64 JRE (17.0.13+11), built on Oct 16 2024 00:00:00 by "temurin" with MS VC++ 17.7 (VS2022)
        |
        |END.
    """.trimMargin()

    val linuxSegfault = """
        |#
        |# A fatal error has been detected by the Java Runtime Environment:
        |#
        |#  SIGSEGV (0xb) at pc=0x00007f2c1a24a1b2, pid=4242, tid=4300
        |#
        |# JRE version: OpenJDK Runtime Environment Temurin-17.0.13+11 (17.0.13+11) (build 17.0.13+11)
        |# Java VM: OpenJDK 64-Bit Server VM Temurin-17.0.13+11 (17.0.13+11, mixed mode, sharing, tiered, compressed oops, compressed class ptrs, g1 gc, linux-amd64)
        |# Problematic frame:
        |# C  [libX11.so.6+0x4a1b2]  _XIOError+0x52
        |#
        |# Core dump will be written. Default location: /home/tester/core.4242
        |#
        |
        |---------------  S U M M A R Y ------------
        |
        |Command Line: -Djdk.gtk.version=0 com.nuvio.app.MainKt
        |
        |Host: Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz, 8 cores, 15G, Ubuntu 24.04.1 LTS
        |Time: Fri Nov 20 22:10:05 2026 WET elapsed time: 95.1 seconds (0d 0h 1m 35s)
        |
        |---------------  T H R E A D  ---------------
        |
        |Current thread (0x00007f2c3c0d4000):  JavaThread "nuvio-player-teardown" daemon [_thread_in_native, id=4300, stack(0x00007f2bf8a00000,0x00007f2bf8b00000)]
        |
        |Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)
        |C  [libX11.so.6+0x4a1b2]  _XIOError+0x52
        |C  [libplayer_bridge.so+0x8c40]  nuvio_bridge_destroy+0x30
        |j  com.nuvio.app.features.player.desktop.NativePlayerBridge.destroy(J)V+0
        |
        |Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)
        |j  com.nuvio.app.features.player.desktop.NativePlayerBridge.destroy(J)V+0
        |j  java.lang.Thread.run()V+11 java.base@17.0.13
        |
        |siginfo: si_signo: 11 (SIGSEGV), si_code: 1 (SEGV_MAPERR), si_addr: 0x0000000000000008
        |
        |Dynamic libraries:
        |55d1c0a00000-55d1c0a01000 r--p 00000000 103:02 1311003                   /tmp/.mount_NuvioXk2/lib/Nuvio
        |55d1c2a00000-55d1c2c21000 rw-p 00000000 00:00 0                          [heap]
        |7f2c1a200000-7f2c1a338000 r-xp 00000000 103:02 4065                      /usr/lib/x86_64-linux-gnu/libX11.so.6.4.0
        |7f2c19e00000-7f2c19e20000 r-xp 00000000 103:02 4065                      /home/tester/.cache/nuvio/native/libplayer_bridge.so
        |7f2c19c00000-7f2c19c20000 r--s 00000000 103:02 4065                      /tmp/.mount_NuvioXk2/lib/app/composeApp.jar
        |7f2c19a00000-7f2c19a20000 r-xp 00000000 103:02 4065                      /usr/lib/x86_64-linux-gnu/libnvidia-glcore.so.550.120 (deleted)
        |
        |Environment Variables:
        |HOME=/home/tester
        |
        |---------------  S Y S T E M  ---------------
        |
        |OS:
        |PRETTY_NAME="Ubuntu 24.04.1 LTS"
        |uname: Linux 6.8.0-49-generic #49-Ubuntu SMP PREEMPT_DYNAMIC x86_64
        |libc: glibc 2.39 NPTL 2.39
        |load average: 1.04 0.87 0.68
        |
        |/proc/meminfo:
        |MemTotal:       16143412 kB
        |
        |CPU: total 8 (initial active 8) (4 cores per cpu, 2 threads per core) family 6 model 142 stepping 10, cx8, cmov, avx, avx2
        |
        |Memory: 4k page, physical 16143412k(1923044k free), swap 2097148k(2097148k free)
        |
        |vm_info: OpenJDK 64-Bit Server VM (17.0.13+11) for linux-amd64 JRE (17.0.13+11), built on Oct 16 2024 00:00:00 by "temurin" with gcc 11.3.0
    """.trimMargin()

    val internalError = """
        |#
        |# A fatal error has been detected by the Java Runtime Environment:
        |#
        |#  Internal Error (g1ConcurrentMark.cpp:1234), pid=777, tid=778
        |#  guarantee(false) failed: unexpected state
        |#
        |# JRE version: OpenJDK Runtime Environment Temurin-17.0.13+11 (17.0.13+11) (build 17.0.13+11)
        |#
        |
        |Command Line: com.nuvio.app.MainKt
        |
        |Current thread (0x00007f2c3c0d4000):  ConcurrentGCThread "G1 Conc#0" [stack: 0x00007f2bf8a00000,0x00007f2bf8b00000] [id=778]
    """.trimMargin()
}
