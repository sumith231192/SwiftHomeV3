package com.example.swifthome;

import android.Manifest;
import android.app.Activity;
import android.os.Build;
import android.speech.RecognizerIntent;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SwiftHome 2: a small, offline Android TV home screen.
 * No network code, no permissions, no libraries, nothing is disabled or uninstalled.
 *
 * Features: app grid with banners, clock, Network / Apps / Settings pills,
 * tiles per row 4-7, motion on/off, move apps left/right/first, hide/show apps (app library),
 * app info, auto-refresh when apps are installed or removed.
 */
public class MainActivity extends Activity {

    private static final class AppItem {
        final String label;
        final String pkg;
        final Intent launch;
        final Drawable art;

        AppItem(String label, String pkg, Intent launch, Drawable art) {
            this.label = label;
            this.pkg = pkg;
            this.launch = launch;
            this.art = art;
        }
    }

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> items = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Collator collator = Collator.getInstance();
    private SharedPreferences prefs;
    private GridView grid;
    private LinearLayout root;
    private TextView notice;
    private LedController led;
    private String ledStatus = "Not connected";
    private boolean ledConnectTried = false;
    private final List<String> foundNames = new ArrayList<>();
    private final List<String> foundAddrs = new ArrayList<>();
    private final LedController.Listener ledListener = new LedController.Listener() {
        @Override public void onStatus(String st) { ledStatus = st; }
        @Override public void onFound(String n, String a) { foundNames.add(n + "  (" + a + ")"); foundAddrs.add(a); }
        @Override public void onScanDone() { showStripPicker(); }
    };
    private AppAdapter adapter;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("swifthome", MODE_PRIVATE);
        led = new LedController(this, ledListener);
        buildUi();
        loadApps();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(24), dp(48), dp(16));
        applyTheme();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Home");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        notice = new TextView(this);
        notice.setTextColor(0xFFFFB74D);
        notice.setTextSize(16);
        notice.setPadding(0, 0, dp(24), 0);
        bar.addView(notice);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(24);
        clock.setPadding(0, 0, dp(24), 0);
        bar.addView(clock);

        Button network = pill("Network");
        network.setOnClickListener(v -> openFirst(
                new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_SETTINGS)));
        bar.addView(network);

        Button assistant = pill("Assistant");
        assistant.setOnClickListener(v -> launchAssistant());
        bar.addView(assistant);

        Button inputs = pill("Inputs");
        inputs.setOnClickListener(v -> showInputs());
        bar.addView(inputs);

        Button ledBtn = pill("LED");
        ledBtn.setOnClickListener(v -> showLedMenu());
        bar.addView(ledBtn);

        Button library = pill("Apps");
        library.setOnClickListener(v -> showLibrary());
        bar.addView(library);

        Button settings = pill("Settings");
        settings.setOnClickListener(v -> openFirst(new Intent(Settings.ACTION_SETTINGS)));
        settings.setLongClickable(true);
        settings.setOnLongClickListener(v -> {   // hold OK = launcher settings
            showLauncherSettings();
            return true;
        });
        bar.addView(settings);

        root.addView(bar);

        grid = new GridView(this);
        grid.setNumColumns(cols());
        grid.setVerticalSpacing(dp(16));
        grid.setHorizontalSpacing(dp(16));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setPadding(dp(8), dp(24), dp(8), dp(24));
        grid.setSelector(selector());
        grid.setDrawSelectorOnTop(true);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(items.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(pos);
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private View last;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (last != null) {
                    last.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start();
                }
                if (v != null && motion()) {
                    v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(dp(8)).setDuration(120).start();
                }
                last = v;
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        root.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private Button pill(String text) {
        Button b = new Button(this);
        b.setText(text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Drawable selector() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(0x33FFFFFF);
        g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    // ------------------------------------------------------- lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
    }

    @Override
    protected void onStop() {
        unregisterReceiver(pkgReceiver);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        grid.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (grid != null && adapter.getCount() > 0) {
            grid.setSelection(0);
            grid.requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        // Home screen should not exit on Back
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        led.close();
        super.onDestroy();
    }

    // ------------------------------------------------------- app data

    private void loadApps() {
        io.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, AppItem> found = new LinkedHashMap<>();
            collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, found);
            collect(pm, Intent.CATEGORY_LAUNCHER, found);
            final List<AppItem> list = new ArrayList<>(found.values());
            Collections.sort(list, (a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                all.clear();
                all.addAll(list);
                rebuildShown();
            });
        });
    }

    private void collect(PackageManager pm, String category, Map<String, AppItem> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
            Drawable art = ri.activityInfo.loadBanner(pm);
            if (art == null) art = ri.loadIcon(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            out.put(pkg, new AppItem(String.valueOf(ri.loadLabel(pm)), pkg, launch, art));
        }
    }

    private Set<String> hiddenSet() {
        return new HashSet<>(prefs.getStringSet("hidden", new HashSet<String>()));
    }

    private List<String> orderList() {
        String s = prefs.getString("order", "");
        List<String> out = new ArrayList<>();
        if (!s.isEmpty()) Collections.addAll(out, s.split(","));
        return out;
    }

    private void rebuildShown() {
        Set<String> hidden = hiddenSet();
        final List<String> order = orderList();
        items.clear();
        for (AppItem a : all) {
            if (!hidden.contains(a.pkg)) items.add(a);
        }
        Collections.sort(items, (x, y) -> {
            int ix = order.indexOf(x.pkg);
            int iy = order.indexOf(y.pkg);
            if (ix < 0) ix = Integer.MAX_VALUE;
            if (iy < 0) iy = Integer.MAX_VALUE;
            if (ix != iy) return Integer.compare(ix, iy);
            return collator.compare(x.label, y.label);
        });
        adapter.notifyDataSetChanged();
        updateNotice();
    }

    private void move(int from, int to) {
        if (to < 0 || to >= items.size() || from == to) return;
        List<String> pk = new ArrayList<>();
        for (AppItem a : items) pk.add(a.pkg);
        String p = pk.remove(from);
        pk.add(to, p);
        prefs.edit().putString("order", TextUtils.join(",", pk)).apply();
        rebuildShown();
        grid.setSelection(to);
    }

    // --------------------------------------------------------- actions

    private void launch(AppItem a) {
        try {
            startActivity(a.launch);
        } catch (ActivityNotFoundException | SecurityException e) {
            loadApps();
        }
    }

    private boolean openFirst(Intent... options) {
        for (Intent i : options) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        return false;
    }

    private void showAppMenu(final int pos) {
        final AppItem a = items.get(pos);
        String[] opts = {"Move left", "Move right", "Move to first", "Hide from Home", "Subscription end date", "App info"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(opts, (d, which) -> {
            switch (which) {
                case 0: move(pos, pos - 1); break;
                case 1: move(pos, pos + 1); break;
                case 2: move(pos, 0); break;
                case 3:
                    Set<String> h = hiddenSet();
                    h.add(a.pkg);
                    prefs.edit().putStringSet("hidden", h).apply();
                    rebuildShown();
                    break;
                case 4:
                    editSubscription(a);
                    break;
                default:
                    openFirst(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", a.pkg, null)));
            }
        }).show();
    }

    private void showLibrary() {
        Set<String> hidden = hiddenSet();
        final String[] names = new String[all.size()];
        final boolean[] on = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = all.get(i).label;
            on[i] = !hidden.contains(all.get(i).pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("Apps shown on Home")
                .setMultiChoiceItems(names, on, (d, which, checked) -> on[which] = checked)
                .setPositiveButton("Done", (d, w) -> {
                    Set<String> h = new HashSet<>();
                    for (int i = 0; i < on.length; i++) {
                        if (!on[i]) h.add(all.get(i).pkg);
                    }
                    prefs.edit().putStringSet("hidden", h).apply();
                    rebuildShown();
                })
                .show();
    }

    private int cols() {
        return prefs.getInt("cols", 5);
    }

    private boolean motion() {
        return prefs.getBoolean("motion", true);
    }

    private void showLauncherSettings() {
        String[] opts = {
                "Tiles per row: " + cols(),
                "Motion: " + (motion() ? "On" : "Off"),
                "Reset app order",
                "Show all hidden apps",
                "Theme: " + THEME_NAMES[theme()],
                "Subscriptions list"
        };
        new AlertDialog.Builder(this).setTitle("Launcher settings").setItems(opts, (d, which) -> {
            switch (which) {
                case 0:
                    int next = cols() >= 7 ? 4 : cols() + 1;
                    prefs.edit().putInt("cols", next).apply();
                    grid.setNumColumns(next);
                    adapter.notifyDataSetChanged();
                    break;
                case 1:
                    prefs.edit().putBoolean("motion", !motion()).apply();
                    break;
                case 2:
                    prefs.edit().remove("order").apply();
                    rebuildShown();
                    break;
                case 3:
                    prefs.edit().remove("hidden").apply();
                    rebuildShown();
                    break;
                case 4:
                    prefs.edit().putInt("theme", (theme() + 1) % THEME_NAMES.length).apply();
                    applyTheme();
                    break;
                default:
                    showSubscriptions();
                    return;
            }
            showLauncherSettings();
        }).setNegativeButton("Close", null).show();
    }

    // ----------------------------------------------- themes, assistant, inputs

    private static final String[] THEME_NAMES = {"Midnight", "Purple", "Forest", "Black"};
    private static final int[][] THEME_COLORS = {
            {0xFF0B1020, 0xFF1B2A4A},
            {0xFF150B20, 0xFF3A1B4A},
            {0xFF0B1A12, 0xFF1B4A2E},
            {0xFF000000, 0xFF111111}
    };

    private int theme() {
        int t = prefs.getInt("theme", 0);
        return (t < 0 || t >= THEME_NAMES.length) ? 0 : t;
    }

    private void applyTheme() {
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, THEME_COLORS[theme()]));
    }

    /** Opens the TV's own assistant (Gemini / Google Assistant). Nothing goes through SwiftHome. */
    private void launchAssistant() {
        boolean ok = openFirst(
                new Intent(Intent.ACTION_VOICE_COMMAND),
                new Intent(Intent.ACTION_ASSIST),
                new Intent("android.intent.action.VOICE_ASSIST"));
        if (!ok) {
            Toast.makeText(this, "No assistant app found. Use the mic button on your remote.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showInputs() {
        try {
            TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            final List<TvInputInfo> list = new ArrayList<>();
            if (tm != null) {
                for (TvInputInfo i : tm.getTvInputList()) {
                    if (i.isPassthroughInput()) list.add(i);
                }
            }
            if (list.isEmpty()) {
                Toast.makeText(this, "No HDMI / AV inputs found", Toast.LENGTH_LONG).show();
                return;
            }
            String[] names = new String[list.size()];
            for (int i = 0; i < names.length; i++) {
                CharSequence custom = list.get(i).loadCustomLabel(this);
                CharSequence base = custom != null ? custom : list.get(i).loadLabel(this);
                names[i] = String.valueOf(base);
            }
            new AlertDialog.Builder(this).setTitle("Inputs").setItems(names, (d, which) ->
                    openFirst(new Intent(Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(list.get(which).getId())))
            ).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Inputs are not available on this TV", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------ subscription tracker
    // Subscription end dates cannot be read from other apps, so you enter them yourself.
    // Stored only on this TV, in this app's private storage.

    private static final long DAY = 86400000L;

    private SimpleDateFormat dateFmt() {
        SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy", Locale.US);
        f.setLenient(false);
        return f;
    }

    private long todayStart() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private long subEnd(String pkg) {
        return prefs.getLong("sub_" + pkg, -1L);
    }

    private int daysLeft(long end) {
        return (int) Math.round((end - todayStart()) / (double) DAY);
    }

    private String daysText(int d) {
        if (d < 0) return "expired";
        if (d == 0) return "ends today";
        return d + "d left";
    }

    private void applySubLabel(TextView label, AppItem a) {
        long end = subEnd(a.pkg);
        if (end < 0) return;
        int d = daysLeft(end);
        label.setText(a.label + " \u2022 " + daysText(d));
        if (d < 0) label.setTextColor(0xFFEF5350);
        else if (d <= 7) label.setTextColor(0xFFFFB74D);
    }

    private void updateNotice() {
        String best = "";
        int bestDays = Integer.MAX_VALUE;
        for (AppItem a : all) {
            long end = subEnd(a.pkg);
            if (end < 0) continue;
            int d = daysLeft(end);
            if (d <= 7 && d < bestDays) {
                bestDays = d;
                best = a.label + ": " + daysText(d);
            }
        }
        notice.setText(best);
    }

    private void editSubscription(final AppItem a) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
        et.setHint("dd/mm/yyyy");
        long end = subEnd(a.pkg);
        if (end >= 0) et.setText(dateFmt().format(new Date(end)));
        new AlertDialog.Builder(this)
                .setTitle(a.label + " ends on")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        Date parsed = dateFmt().parse(et.getText().toString().trim());
                        prefs.edit().putLong("sub_" + a.pkg, parsed.getTime()).apply();
                        adapter.notifyDataSetChanged();
                        updateNotice();
                    } catch (ParseException e) {
                        Toast.makeText(this, "Use dd/mm/yyyy, e.g. 31/12/2026", Toast.LENGTH_LONG).show();
                    }
                })
                .setNeutralButton("Clear", (d, w) -> {
                    prefs.edit().remove("sub_" + a.pkg).apply();
                    adapter.notifyDataSetChanged();
                    updateNotice();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSubscriptions() {
        final List<AppItem> subs = new ArrayList<>();
        for (AppItem a : all) {
            if (subEnd(a.pkg) >= 0) subs.add(a);
        }
        if (subs.isEmpty()) {
            Toast.makeText(this, "No dates yet. Hold OK on an app, then Subscription end date.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Collections.sort(subs, (x, y) -> Long.compare(subEnd(x.pkg), subEnd(y.pkg)));
        String[] rows = new String[subs.size()];
        for (int i = 0; i < rows.length; i++) {
            long end = subEnd(subs.get(i).pkg);
            rows[i] = subs.get(i).label + "  -  " + dateFmt().format(new Date(end))
                    + "  (" + daysText(daysLeft(end)) + ")";
        }
        new AlertDialog.Builder(this).setTitle("Subscriptions").setItems(rows, null)
                .setNegativeButton("Close", null).show();
    }

    // ------------------------------------------------------- LED strip + voice

    private static final String[] LED_NAMES = {"Red", "Green", "Blue", "White", "Warm white",
            "Yellow", "Orange", "Purple", "Pink", "Cyan"};
    private static final int[][] LED_RGB = {{255, 0, 0}, {0, 255, 0}, {0, 0, 255}, {255, 255, 255},
            {255, 170, 60}, {255, 255, 0}, {255, 100, 0}, {128, 0, 255}, {255, 60, 150}, {0, 255, 255}};

    private int ledR = 255, ledG = 255, ledB = 255;

    private int ledBright() {
        return prefs.getInt("led_bright", 100);
    }

    private void applyLed(boolean off) {
        int b = off ? 0 : ledBright();
        boolean ok = led.setColor(ledR * b / 100, ledG * b / 100, ledB * b / 100);
        if (!ok) Toast.makeText(this, "Strip not connected. Open LED, then Find / choose strip.",
                Toast.LENGTH_LONG).show();
    }

    private void showLedMenu() {
        String addr = prefs.getString("led_addr", "");
        if (!led.isReady() && !addr.isEmpty() && !ledConnectTried && ledPermissions(false)) {
            ledConnectTried = true;
            led.connect(addr);
        }
        List<String> rows = new ArrayList<>();
        rows.add("Status: " + ledStatus + "  (select to refresh)");
        rows.add("Find / choose strip");
        rows.add("Voice command");
        rows.add("Brighter (now " + ledBright() + "%)");
        rows.add("Dimmer");
        rows.add("Off");
        for (String n : LED_NAMES) rows.add(n);
        new AlertDialog.Builder(this).setTitle("LED strip")
                .setItems(rows.toArray(new String[0]), (d, which) -> {
                    switch (which) {
                        case 0: break;
                        case 1: findStrip(); return;
                        case 2: startVoice(); return;
                        case 3:
                            prefs.edit().putInt("led_bright", Math.min(100, ledBright() + 20)).apply();
                            applyLed(false);
                            break;
                        case 4:
                            prefs.edit().putInt("led_bright", Math.max(10, ledBright() - 20)).apply();
                            applyLed(false);
                            break;
                        case 5: applyLed(true); break;
                        default:
                            int[] c = LED_RGB[which - 6];
                            ledR = c[0]; ledG = c[1]; ledB = c[2];
                            applyLed(false);
                    }
                    showLedMenu();
                })
                .setNegativeButton("Close", null).show();
    }

    private boolean ledPermissions(boolean ask) {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (need.isEmpty()) return true;
        if (ask) requestPermissions(need.toArray(new String[0]), 77);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != 77) return;
        boolean ok = res.length > 0;
        for (int r : res) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) findStrip();
        else Toast.makeText(this, "Bluetooth permission was denied", Toast.LENGTH_LONG).show();
    }

    private void findStrip() {
        if (!ledPermissions(true)) return;
        foundNames.clear();
        foundAddrs.clear();
        led.scan();
        Toast.makeText(this, "Scanning for 8 seconds. Keep the strip powered and close.",
                Toast.LENGTH_LONG).show();
    }

    private void showStripPicker() {
        if (foundNames.isEmpty()) {
            Toast.makeText(this, "No LED strip found (" + ledStatus + ")", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Choose your strip")
                .setItems(foundNames.toArray(new String[0]), (d, w) -> {
                    String a = foundAddrs.get(w);
                    prefs.edit().putString("led_addr", a).apply();
                    ledConnectTried = true;
                    led.connect(a);
                    showLedMenu();
                }).show();
    }

    /** Uses the TV's own speech screen, so SwiftHome never gets the microphone. */
    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a colour, off, brighter or dimmer");
        try {
            startActivityForResult(i, 88);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "This TV has no speech recognizer available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 88 || res != RESULT_OK || data == null) return;
        ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (r != null && !r.isEmpty()) handleVoice(r.get(0).toLowerCase(Locale.ROOT));
    }

    private void handleVoice(String t) {
        Toast.makeText(this, "Heard: " + t, Toast.LENGTH_SHORT).show();
        if (t.contains("off")) { applyLed(true); return; }
        if (t.contains("bright")) {
            prefs.edit().putInt("led_bright", Math.min(100, ledBright() + 20)).apply();
            applyLed(false);
            return;
        }
        if (t.contains("dim")) {
            prefs.edit().putInt("led_bright", Math.max(10, ledBright() - 20)).apply();
            applyLed(false);
            return;
        }
        int best = -1, bestLen = 0;
        for (int i = 0; i < LED_NAMES.length; i++) {
            String n = LED_NAMES[i].toLowerCase(Locale.ROOT);
            if (t.contains(n) && n.length() > bestLen) { best = i; bestLen = n.length(); }
        }
        if (best >= 0) {
            ledR = LED_RGB[best][0]; ledG = LED_RGB[best][1]; ledB = LED_RGB[best][2];
            applyLed(false);
        } else if (t.contains(" on") || t.equals("on")) {
            applyLed(false);
        }
    }

    // --------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int imgWidth() {
        int total = getResources().getDisplayMetrics().widthPixels - dp(112);
        int spacing = dp(16) * (cols() - 1);
        return Math.max(dp(80), (total - spacing) / cols() - dp(16));
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout cell;
            ImageView img;
            TextView label;
            if (convert == null) {
                cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(8), dp(8), dp(8), dp(8));
                img = new ImageView(MainActivity.this);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                cell.addView(img);
                label = new TextView(MainActivity.this);
                label.setTextColor(Color.WHITE);
                label.setTextSize(16);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setTag(new View[]{img, label});
            } else {
                cell = (LinearLayout) convert;
                View[] t = (View[]) cell.getTag();
                img = (ImageView) t[0];
                label = (TextView) t[1];
            }
            int w = imgWidth();
            img.setLayoutParams(new LinearLayout.LayoutParams(w, w * 9 / 16));
            cell.setScaleX(1f);
            cell.setScaleY(1f);
            cell.setTranslationZ(0f);
            AppItem a = items.get(pos);
            img.setImageDrawable(a.art);
            label.setText(a.label);
            label.setTextColor(Color.WHITE);
            applySubLabel(label, a);
            return cell;
        }
    }
}
