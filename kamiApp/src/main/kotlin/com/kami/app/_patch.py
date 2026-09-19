def edit(p, pairs):
    src = open(p, encoding="utf-8").read()
    for old, new in pairs:
        assert old in src, p + " MISSING: " + old[:70]
        src = src.replace(old, new, 1)
    open(p, "w", encoding="utf-8", newline="\n").write(src)
    print(p, "ok")


# 1) MainActivity: auto keep-alive on grant + ON_RESUME IME restore + BioSection block
edit("MainActivity.kt", [
    (
        """    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    private fun refresh() {
        shizukuAlive.value = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        shizukuGranted.value = ShizukuRunner.granted()
    }""",
        """    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** Doze exemptions persist in the system — apply once per process. */
    private var keepAliveApplied = false

    private fun refresh() {
        shizukuAlive.value = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
        shizukuGranted.value = ShizukuRunner.granted()
        if (shizukuGranted.value && !keepAliveApplied) {
            keepAliveApplied = true
            Thread { runCatching { KeepAlive.apply() } }.start()
        }
    }""",
    ),
    (
        """        // targetSdk 35+ enforces edge-to-edge; draw edge to edge on purpose
        // and pad content with safeDrawing (status bar + nav + IME) below.
        enableEdgeToEdge()""",
        """        // Back at the foreground the agent is not driving the screen any
        // more — hand the user's own keyboard back if we borrowed it.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                }
            },
        )
        // targetSdk 35+ enforces edge-to-edge; draw edge to edge on purpose
        // and pad content with safeDrawing (status bar + nav + IME) below.
        enableEdgeToEdge()""",
    ),
    (
        """            Switch(
                checked = BioGate.enabled(context) && BioGate.available(context),
                onCheckedChange = { BioGate.setEnabled(context, it) },
                enabled = BioGate.available(context),
            )
        }
    }""",
        """            Switch(
                checked = BioGate.enabled(context) && BioGate.available(context),
                onCheckedChange = { BioGate.setEnabled(context, it) },
                enabled = BioGate.available(context),
            )
        }

        Text("后台保活（定时任务可靠触发）", style = MaterialTheme.typography.titleSmall)
        Text(
            "经 Shizuku 把本应用加入 Doze 白名单并允许后台运行，闹钟不再被系统限流；" +
                "持久生效、无任何后台进程。Shizuku 授权后也会自动应用一次",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var keepMsg by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        if (keepMsg.isNotEmpty()) {
            Text(
                keepMsg,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    keepMsg = KeepAlive.apply()
                }
            },
            enabled = shizukuGranted,
        ) { Text("应用保活白名单") }
    }""",
    ),
])

# 2) ChatScreen: restore IME when every run is done
edit("ChatScreen.kt", [
    (
        """                        } finally {
                            AgentOverlayState.refresh()
                            if (!AgentOverlayState.running.value) {
                                runCatching {
                                    appCtx.stopService(Intent(appCtx, AgentOverlayService::class.java))
                                }
                            }
                        }""",
        """                        } finally {
                            AgentOverlayState.refresh()
                            if (!AgentOverlayState.running.value) {
                                Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                                runCatching {
                                    appCtx.stopService(Intent(appCtx, AgentOverlayService::class.java))
                                }
                            }
                        }""",
    ),
])

# 3) StopReceiver: forced stop also restores the IME
edit("StopReceiver.kt", [
    (
        """    override fun onReceive(context: Context, intent: Intent) {
        AgentOverlayState.cancelAll()
        context.stopService(Intent(context, AgentOverlayService::class.java))
    }""",
        """    override fun onReceive(context: Context, intent: Intent) {
        AgentOverlayState.cancelAll()
        Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
        context.stopService(Intent(context, AgentOverlayService::class.java))
    }""",
    ),
])

# 4) Overlay service: run ended in background -> restore IME too
edit("AgentOverlayService.kt", [
    (
        """            if (!AgentOverlayState.running.value) {
                AgentOverlayState.screenSeen.value = false
                detach()
                stopSelf()
                return
            }""",
        """            if (!AgentOverlayState.running.value) {
                AgentOverlayState.screenSeen.value = false
                detach()
                Thread { ScreenControl.restoreImeIfNeeded(force = true) }.start()
                stopSelf()
                return
            }""",
    ),
])

# 5) ReminderScheduler: setAlarmClock (clock-app grade, Doze-proof)
edit("ReminderStore.kt", [
    (
        """    /** Re-arm every reminder from scratch (idempotent). */
    fun scheduleAll(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val inexact = Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()
        ReminderStore.all().forEach { r ->
            val pi = pendingIntent(appCtx, r.id)
            am.cancel(pi)
            if (!r.enabled) return@forEach
            val at = nextAt(r)?.timeInMillis ?: return@forEach
            if (inexact) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }""",
        """    /** Re-arm every reminder from scratch (idempotent). */
    fun scheduleAll(context: Context) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Tapping the alarm icon lands back in the app.
        val showPi = PendingIntent.getActivity(
            appCtx,
            0,
            Intent(appCtx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ReminderStore.all().forEach { r ->
            val pi = pendingIntent(appCtx, r.id)
            am.cancel(pi)
            if (!r.enabled) return@forEach
            val at = nextAt(r)?.timeInMillis ?: return@forEach
            // setAlarmClock = clock-app grade: fires in Doze, exempt from
            // standby buckets, no SCHEDULE_EXACT_ALARM grant needed. The
            // status bar shows an alarm icon while anything is armed.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi), pi)
        }
    }""",
    ),
])

# 6) IME naming: clearly Kami's own, not the community ADBKeyBoard
edit("../../AndroidManifest.xml", [
    ('android:label="Kami ADB Keyboard"', 'android:label="Kami 输入法"'),
])
edit("../../res/xml/method.xml", [
    ('android:label="Kami ADB"', 'android:label="Kami"'),
])
