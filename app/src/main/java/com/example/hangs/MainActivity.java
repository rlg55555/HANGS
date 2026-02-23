package com.example.hangs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    // ─── Timer durations ──────────────────────────────────────────────────────
    private long onDurationSec      = 10;
    private long offDurationSec     = 40;
    private long sessionDurationSec = 600; // 10 minutes default; 0 = unlimited

    // ─── Runtime state ────────────────────────────────────────────────────────
    private boolean isRunning   = false;
    private int  cycleCount     = 0;
    private long sessionElapsedMs = 0;
    private long sessionStartMs   = 0;

    private CountDownTimer cycleTimer;
    private CountDownTimer sessionTimer;
    private boolean currentlyOn = false;

    // Warning beeps
    private static final long WARNING_SECONDS = 5;
    private final Handler warningHandler = new Handler(Looper.getMainLooper());
    private Runnable warningRunnable;

    // Hold-to-repeat
    private final Handler holdHandler = new Handler(Looper.getMainLooper());
    private Runnable holdRunnable;
    private static final long HOLD_INITIAL_DELAY = 400;
    private static final long HOLD_REPEAT_DELAY  = 80;

    // Audio / haptics
    private ToneGenerator toneGen;
    private Vibrator vibrator;

    // Session log persistence
    private static final String PREFS_NAME    = "hangs_sessions";
    private static final String KEY_LOG       = "session_log";
    private static final int    MAX_LOG_ENTRIES = 50;

    // ─── Views ────────────────────────────────────────────────────────────────
    // Phase controls
    private View      statusIndicator;
    private TextView  tvPhaseLabel, tvPhaseCountdown, tvCycleCount;
    private ProgressBar pbPhase, pbSession;
    private Button    btnStartStop;

    // ON card
    private TextView    tvOnDisplay;
    private ImageButton btnOnHH_Up, btnOnHH_Dn, btnOnMM_Up, btnOnMM_Dn, btnOnSS_Up, btnOnSS_Dn;
    private EditText    etOnDirect;

    // OFF card
    private TextView    tvOffDisplay;
    private ImageButton btnOffHH_Up, btnOffHH_Dn, btnOffMM_Up, btnOffMM_Dn, btnOffSS_Up, btnOffSS_Dn;
    private EditText    etOffDirect;

    // SESSION card
    private TextView    tvSessionDisplay, tvSessionRemaining;
    private ImageButton btnSesHH_Up, btnSesHH_Dn, btnSesMM_Up, btnSesMM_Dn, btnSesSS_Up, btnSesSS_Dn;
    private EditText    etSesDirect;

    // Log
    private LinearLayout logContainer;
    private Button       btnClearLog;
    private TextView     tvLogEmpty;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        try { toneGen = new ToneGenerator(AudioManager.STREAM_ALARM, 90); }
        catch (Exception e) { toneGen = null; }

        bindViews();
        wireOnControls();
        wireOffControls();
        wireSessionControls();
        wireTapToType();
        updateOnDisplay();
        updateOffDisplay();
        updateSessionDisplay();
        resetRunningUI();
        loadAndDisplayLog();

        btnStartStop.setOnClickListener(v -> { if (isRunning) stopTimer(); else startTimer(); });
        btnClearLog.setOnClickListener(v -> clearLog());
    }

    // ─── View binding ─────────────────────────────────────────────────────────
    private void bindViews() {
        statusIndicator  = findViewById(R.id.statusIndicator);
        tvPhaseLabel     = findViewById(R.id.tvPhaseLabel);
        tvPhaseCountdown = findViewById(R.id.tvPhaseCountdown);
        tvCycleCount     = findViewById(R.id.tvCycleCount);
        pbPhase          = findViewById(R.id.pbPhase);
        pbSession        = findViewById(R.id.pbSession);
        btnStartStop     = findViewById(R.id.btnStartStop);
        tvSessionRemaining = findViewById(R.id.tvSessionRemaining);

        tvOnDisplay  = findViewById(R.id.tvOnDisplay);
        tvOffDisplay = findViewById(R.id.tvOffDisplay);
        tvSessionDisplay = findViewById(R.id.tvSessionDisplay);

        btnOnHH_Up = findViewById(R.id.btnOnHH_Up);   btnOnHH_Dn = findViewById(R.id.btnOnHH_Dn);
        btnOnMM_Up = findViewById(R.id.btnOnMM_Up);   btnOnMM_Dn = findViewById(R.id.btnOnMM_Dn);
        btnOnSS_Up = findViewById(R.id.btnOnSS_Up);   btnOnSS_Dn = findViewById(R.id.btnOnSS_Dn);

        btnOffHH_Up = findViewById(R.id.btnOffHH_Up); btnOffHH_Dn = findViewById(R.id.btnOffHH_Dn);
        btnOffMM_Up = findViewById(R.id.btnOffMM_Up); btnOffMM_Dn = findViewById(R.id.btnOffMM_Dn);
        btnOffSS_Up = findViewById(R.id.btnOffSS_Up); btnOffSS_Dn = findViewById(R.id.btnOffSS_Dn);

        btnSesHH_Up = findViewById(R.id.btnSesHH_Up); btnSesHH_Dn = findViewById(R.id.btnSesHH_Dn);
        btnSesMM_Up = findViewById(R.id.btnSesMM_Up); btnSesMM_Dn = findViewById(R.id.btnSesMM_Dn);
        btnSesSS_Up = findViewById(R.id.btnSesSS_Up); btnSesSS_Dn = findViewById(R.id.btnSesSS_Dn);

        etOnDirect  = findViewById(R.id.etOnDirect);
        etOffDirect = findViewById(R.id.etOffDirect);
        etSesDirect = findViewById(R.id.etSesDirect);

        logContainer = findViewById(R.id.logContainer);

        btnClearLog  = findViewById(R.id.btnClearLog);
        tvLogEmpty   = findViewById(R.id.tvLogEmpty);
    }

    // ─── Control wiring ───────────────────────────────────────────────────────
    private void wireOnControls() {
        setupHold(btnOnHH_Up, () -> { onDurationSec = clamp(onDurationSec + 3600); updateOnDisplay(); });
        setupHold(btnOnHH_Dn, () -> { onDurationSec = clamp(onDurationSec - 3600); updateOnDisplay(); });
        setupHold(btnOnMM_Up, () -> { onDurationSec = clamp(onDurationSec + 60);   updateOnDisplay(); });
        setupHold(btnOnMM_Dn, () -> { onDurationSec = clamp(onDurationSec - 60);   updateOnDisplay(); });
        setupHold(btnOnSS_Up, () -> { onDurationSec = clamp(onDurationSec + 1);    updateOnDisplay(); });
        setupHold(btnOnSS_Dn, () -> { onDurationSec = clamp(onDurationSec - 1);    updateOnDisplay(); });
    }

    private void wireOffControls() {
        setupHold(btnOffHH_Up, () -> { offDurationSec = clamp(offDurationSec + 3600); updateOffDisplay(); });
        setupHold(btnOffHH_Dn, () -> { offDurationSec = clamp(offDurationSec - 3600); updateOffDisplay(); });
        setupHold(btnOffMM_Up, () -> { offDurationSec = clamp(offDurationSec + 60);   updateOffDisplay(); });
        setupHold(btnOffMM_Dn, () -> { offDurationSec = clamp(offDurationSec - 60);   updateOffDisplay(); });
        setupHold(btnOffSS_Up, () -> { offDurationSec = clamp(offDurationSec + 1);    updateOffDisplay(); });
        setupHold(btnOffSS_Dn, () -> { offDurationSec = clamp(offDurationSec - 1);    updateOffDisplay(); });
    }

    private void wireSessionControls() {
        setupHold(btnSesHH_Up, () -> { sessionDurationSec = clampSession(sessionDurationSec + 3600); updateSessionDisplay(); });
        setupHold(btnSesHH_Dn, () -> { sessionDurationSec = clampSession(sessionDurationSec - 3600); updateSessionDisplay(); });
        setupHold(btnSesMM_Up, () -> { sessionDurationSec = clampSession(sessionDurationSec + 60);   updateSessionDisplay(); });
        setupHold(btnSesMM_Dn, () -> { sessionDurationSec = clampSession(sessionDurationSec - 60);   updateSessionDisplay(); });
        setupHold(btnSesSS_Up, () -> { sessionDurationSec = clampSession(sessionDurationSec + 1);    updateSessionDisplay(); });
        setupHold(btnSesSS_Dn, () -> { sessionDurationSec = clampSession(sessionDurationSec - 1);    updateSessionDisplay(); });
    }

    private void wireTapToType() {
        wireField(etOnDirect,  true,  false);
        wireField(etOffDirect, false, false);
        wireField(etSesDirect, false, true);
    }

    private void wireField(EditText et, boolean isOn, boolean isSession) {
        et.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) { applyEntry(et, isOn, isSession); return true; }
            return false;
        });
        et.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) applyEntry(et, isOn, isSession); });
    }

    private void applyEntry(EditText et, boolean isOn, boolean isSession) {
        String raw = et.getText().toString().trim();
        if (raw.isEmpty()) return;
        try {
            long seconds;
            if (raw.contains(":")) {
                String[] parts = raw.split(":");
                seconds = 0;
                for (String p : parts) seconds = seconds * 60 + Long.parseLong(p.trim());
            } else {
                seconds = Long.parseLong(raw);
            }
            if (isSession) {
                sessionDurationSec = clampSession(seconds);
                updateSessionDisplay();
            } else if (isOn) {
                onDurationSec = clamp(seconds);
                updateOnDisplay();
            } else {
                offDurationSec = clamp(seconds);
                updateOffDisplay();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Enter seconds or H:MM:SS", Toast.LENGTH_SHORT).show();
        }
        et.setText("");
        hideKeyboard(et);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ─── Display helpers ──────────────────────────────────────────────────────
    private void updateOnDisplay()      { tvOnDisplay.setText(formatHMS(onDurationSec)); }
    private void updateOffDisplay()     { tvOffDisplay.setText(formatHMS(offDurationSec)); }
    private void updateSessionDisplay() {
        tvSessionDisplay.setText(sessionDurationSec == 0 ? "∞" : formatHMS(sessionDurationSec));
    }

    private String formatHMS(long totalSec) {
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private long clamp(long v)        { return Math.max(0, Math.min(1000L * 3600L, v)); }
    private long clampSession(long v) { return Math.max(0, Math.min(1000L * 3600L, v)); }

    // ─── Hold-to-repeat ───────────────────────────────────────────────────────
    @SuppressLint("ClickableViewAccessibility")
    private void setupHold(View btn, Runnable action) {
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    action.run();
                    holdRunnable = new Runnable() {
                        @Override public void run() { action.run(); holdHandler.postDelayed(this, HOLD_REPEAT_DELAY); }
                    };
                    holdHandler.postDelayed(holdRunnable, HOLD_INITIAL_DELAY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    holdHandler.removeCallbacks(holdRunnable);
                    return true;
            }
            return false;
        });
    }

    // ─── Timer logic ──────────────────────────────────────────────────────────
    private void startTimer() {
        if (onDurationSec == 0 && offDurationSec == 0) {
            Toast.makeText(this, "Set at least one duration > 0", Toast.LENGTH_SHORT).show();
            return;
        }
        isRunning = true;
        cycleCount = 0;
        sessionElapsedMs = 0;
        sessionStartMs = System.currentTimeMillis();
        lockControls(true);
        btnStartStop.setText("STOP");
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.stop_red));

        // Start session countdown if a duration is set
        if (sessionDurationSec > 0) {
            pbSession.setMax((int) sessionDurationSec);
            pbSession.setProgress((int) sessionDurationSec);
            tvSessionRemaining.setText("Session: " + formatHMS(sessionDurationSec));
            sessionTimer = new CountDownTimer(sessionDurationSec * 1000, 500) {
                @Override public void onTick(long ms) {
                    int sec = (int)(ms / 1000);
                    pbSession.setProgress(sec);
                    tvSessionRemaining.setText("Session: " + formatHMS(sec));
                }
                @Override public void onFinish() {
                    if (!isRunning) return;
                    pbSession.setProgress(0);
                    tvSessionRemaining.setText("Session: complete!");
                    onSessionComplete();
                }
            }.start();
        } else {
            pbSession.setProgress(0);
            tvSessionRemaining.setText("Session: unlimited");
        }

        startPhase(true);
    }

    private void startPhase(boolean on) {
        currentlyOn = on;
        long phaseSec = on ? onDurationSec : offDurationSec;

        if (phaseSec == 0) {
            if (on) { cycleCount++; startPhase(false); } else { startPhase(true); }
            return;
        }

        cancelWarning();
        pbPhase.setMax((int) phaseSec);
        pbPhase.setProgress((int) phaseSec);
        updatePhaseUI(on, phaseSec * 1000);
        playChime(on);
        vibrateShort();
        scheduleWarning(phaseSec);

        cycleTimer = new CountDownTimer(phaseSec * 1000, 250) {
            @Override public void onTick(long ms) {
                updatePhaseUI(on, ms);
                pbPhase.setProgress((int)(ms / 1000));
            }
            @Override public void onFinish() {
                if (!isRunning) return;
                cancelWarning();
                if (on) { cycleCount++; updateCycleCount(); startPhase(false); }
                else    { startPhase(true); }
            }
        }.start();
    }

    private void onSessionComplete() {
        // Stop all timers
        if (cycleTimer != null) { cycleTimer.cancel(); cycleTimer = null; }
        cancelWarning();
        isRunning = false;
        lockControls(false);

        // Victory fanfare!
        playVictoryChime();
        vibrateVictory();

        // Update UI
        tvPhaseLabel.setText("DONE!");
        tvPhaseLabel.setTextColor(ContextCompat.getColor(this, R.color.session_gold));
        statusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.session_gold));
        tvPhaseCountdown.setText("✓");
        btnStartStop.setText("START");
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.start_green));

        // Save to log
        saveSession();
        loadAndDisplayLog();
    }

    private void stopTimer() {
        if (cycleTimer != null)   { cycleTimer.cancel();   cycleTimer = null; }
        if (sessionTimer != null) { sessionTimer.cancel(); sessionTimer = null; }
        cancelWarning();
        isRunning = false;
        lockControls(false);

        // Save partial session if at least one cycle completed
        if (cycleCount > 0) { saveSession(); loadAndDisplayLog(); }

        resetRunningUI();
    }

    private void updatePhaseUI(boolean on, long ms) {
        long sec = (ms + 999) / 1000;
        tvPhaseCountdown.setText(formatHMS(sec));
        if (on) {
            tvPhaseLabel.setText("ON");
            tvPhaseLabel.setTextColor(ContextCompat.getColor(this, R.color.on_green));
            statusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.on_green));
            pbPhase.setProgressTintList(ContextCompat.getColorStateList(this, R.color.on_green));
        } else {
            tvPhaseLabel.setText("OFF");
            tvPhaseLabel.setTextColor(ContextCompat.getColor(this, R.color.off_gray));
            statusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.off_gray));
            pbPhase.setProgressTintList(ContextCompat.getColorStateList(this, R.color.off_gray));
        }
        updateCycleCount();
    }

    private void updateCycleCount() { tvCycleCount.setText("Cycles: " + cycleCount); }

    private void resetRunningUI() {
        tvPhaseLabel.setText("READY");
        tvPhaseLabel.setTextColor(ContextCompat.getColor(this, R.color.off_gray));
        statusIndicator.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.off_gray));
        tvPhaseCountdown.setText("--:--");
        tvCycleCount.setText("Cycles: 0");
        pbPhase.setProgress(0);
        pbSession.setProgress(0);
        tvSessionRemaining.setText("Session: " + (sessionDurationSec > 0 ? formatHMS(sessionDurationSec) : "unlimited"));
        btnStartStop.setText("START");
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.start_green));
    }

    private void lockControls(boolean lock) {
        int[] ids = {
            R.id.btnOnHH_Up, R.id.btnOnHH_Dn, R.id.btnOnMM_Up, R.id.btnOnMM_Dn,
            R.id.btnOnSS_Up, R.id.btnOnSS_Dn, R.id.btnOffHH_Up, R.id.btnOffHH_Dn,
            R.id.btnOffMM_Up, R.id.btnOffMM_Dn, R.id.btnOffSS_Up, R.id.btnOffSS_Dn,
            R.id.btnSesHH_Up, R.id.btnSesHH_Dn, R.id.btnSesMM_Up, R.id.btnSesMM_Dn,
            R.id.btnSesSS_Up, R.id.btnSesSS_Dn,
            R.id.etOnDirect, R.id.etOffDirect, R.id.etSesDirect
        };
        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) { v.setEnabled(!lock); v.setAlpha(lock ? 0.35f : 1f); }
        }
    }

    // ─── Audio ────────────────────────────────────────────────────────────────
    private void playChime(boolean on) {
        if (toneGen == null) return;
        if (on) {
            toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 180);
            warningHandler.postDelayed(() -> {
                if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 250);
            }, 250);
        } else {
            toneGen.startTone(ToneGenerator.TONE_CDMA_LOW_L, 350);
        }
    }

    /** Victory fanfare: ascending three-tone sequence */
    private void playVictoryChime() {
        if (toneGen == null) return;
        // Low → Mid → High, then a final long high tone
        toneGen.startTone(ToneGenerator.TONE_CDMA_LOW_L, 200);
        warningHandler.postDelayed(() -> {
            if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_CDMA_MED_L, 200);
        }, 250);
        warningHandler.postDelayed(() -> {
            if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 200);
        }, 500);
        warningHandler.postDelayed(() -> {
            if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 600);
        }, 750);
    }

    private void scheduleWarning(long phaseSec) {
        if (phaseSec <= WARNING_SECONDS + 1) return;
        long delayMs = (phaseSec - WARNING_SECONDS) * 1000;
        warningRunnable = new Runnable() {
            int beepsLeft = (int) WARNING_SECONDS;
            @Override public void run() {
                if (!isRunning) return;
                if (toneGen != null) toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 120);
                beepsLeft--;
                if (beepsLeft > 0) warningHandler.postDelayed(this, 1000);
            }
        };
        warningHandler.postDelayed(warningRunnable, delayMs);
    }

    private void cancelWarning() {
        if (warningRunnable != null) { warningHandler.removeCallbacks(warningRunnable); warningRunnable = null; }
    }

    // ─── Vibration ────────────────────────────────────────────────────────────
    private void vibrateShort() {
        if (vibrator != null && vibrator.hasVibrator())
            vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    private void vibrateVictory() {
        if (vibrator != null && vibrator.hasVibrator()) {
            long[] pattern = {0, 100, 100, 100, 100, 400};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
        }
    }

    // ─── Session logging ──────────────────────────────────────────────────────
    private void saveSession() {
        long actualDurationMs = System.currentTimeMillis() - sessionStartMs;
        String date = new SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(new Date());

        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONArray log = new JSONArray(prefs.getString(KEY_LOG, "[]"));

            JSONObject entry = new JSONObject();
            entry.put("date", date);
            entry.put("cycles", cycleCount);
            entry.put("on_sec", onDurationSec);
            entry.put("off_sec", offDurationSec);
            entry.put("session_sec", sessionDurationSec);
            entry.put("actual_ms", actualDurationMs);
            entry.put("completed", !isRunning && sessionTimer != null); // true = finished naturally

            // Prepend newest first
            JSONArray newLog = new JSONArray();
            newLog.put(entry);
            for (int i = 0; i < Math.min(log.length(), MAX_LOG_ENTRIES - 1); i++) newLog.put(log.get(i));

            prefs.edit().putString(KEY_LOG, newLog.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void loadAndDisplayLog() {
        logContainer.removeAllViews();
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            JSONArray log = new JSONArray(prefs.getString(KEY_LOG, "[]"));

            if (log.length() == 0) {
                tvLogEmpty.setVisibility(View.VISIBLE);
                return;
            }
            tvLogEmpty.setVisibility(View.GONE);

            for (int i = 0; i < log.length(); i++) {
                JSONObject e = log.getJSONObject(i);
                long actualSec = e.getLong("actual_ms") / 1000;

                // Build card view programmatically
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.card_log);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, dpToPx(10));
                card.setLayoutParams(lp);
                card.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

                // Date row
                TextView tvDate = makeText(e.getString("date"), 12, R.color.label, false);
                card.addView(tvDate);

                // Cycles row
                String cycleStr = e.getInt("cycles") + " cycles  ·  "
                        + formatHMS(e.getLong("on_sec")) + " on / "
                        + formatHMS(e.getLong("off_sec")) + " off";
                TextView tvCycles = makeText(cycleStr, 15, R.color.text_primary, true);
                card.addView(tvCycles);

                // Duration row
                long sesSec = e.getLong("session_sec");
                String durStr = "Duration: " + formatHMS(actualSec)
                        + (sesSec > 0 ? "  /  Goal: " + formatHMS(sesSec) : "  (unlimited)");
                TextView tvDur = makeText(durStr, 12, R.color.label, false);
                card.addView(tvDur);

                logContainer.addView(card);
            }
        } catch (JSONException ex) {
            ex.printStackTrace();
        }
    }

    private TextView makeText(String text, int sp, int colorRes, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        if (bold) tv.setTypeface(android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dpToPx(3));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void clearLog() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_LOG).apply();
        logContainer.removeAllViews();
        tvLogEmpty.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Session log cleared", Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (cycleTimer != null)   cycleTimer.cancel();
        if (sessionTimer != null) sessionTimer.cancel();
        cancelWarning();
        holdHandler.removeCallbacksAndMessages(null);
        warningHandler.removeCallbacksAndMessages(null);
        if (toneGen != null) { toneGen.release(); toneGen = null; }
    }
}
