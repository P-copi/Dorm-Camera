package com.pcopi.dormcamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

/**
 * V0.2.4 interactive map picker.
 * Uses Leaflet with OpenStreetMap / satellite tiles so no Google Maps API key is required.
 */
public final class MapPickerActivity extends AppCompatActivity {
    public static final String EXTRA_LAT = "map_lat";
    public static final String EXTRA_LON = "map_lon";
    public static final String EXTRA_LABEL = "map_label";
    private static final int REQ_LOCATION = 2241;

    private WebView webView;
    private TextView coordinateText;
    private TextView statusText;
    private EditText searchInput;
    private double selectedLat = Double.NaN;
    private double selectedLon = Double.NaN;
    private boolean waitingPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();

        double lat = getIntent().getDoubleExtra(EXTRA_LAT, 15.8700);
        double lon = getIntent().getDoubleExtra(EXTRA_LON, 100.9925);
        boolean hasInitial = getIntent().hasExtra(EXTRA_LAT) && getIntent().hasExtra(EXTRA_LON);
        loadMap(lat, lon, hasInitial ? 16 : 6, hasInitial);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.setBackgroundColor(0xFF050506);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        PressFeedbackButton back = button("←", 15f);
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(42)));
        TextView title = text("เลือกตำแหน่งจากแผนที่", 20f, 0xFFFFFFFF);
        title.setPadding(dp(10), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        root.addView(top);

        TextView hint = text("แตะจุดบนแผนที่ หรือเลื่อนแผนที่แล้วกด “เลือกจุดกลาง”", 12f, 0xFFB9BBC2);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(4), 0, dp(7));
        root.addView(hint);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setHint("ค้นหาสถานที่ เช่น ดอนเจดีย์ สุพรรณบุรี");
        searchInput.setTextColor(0xFFFFFFFF);
        searchInput.setHintTextColor(0xFF8F929A);
        searchInput.setTextSize(14f);
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(48), 1f));
        PressFeedbackButton search = button("ค้นหา", 13f);
        search.setOnClickListener(v -> searchPlace());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(82), dp(44));
        sp.setMarginStart(dp(6));
        searchRow.addView(search, sp);
        root.addView(searchRow);

        FrameLayout mapFrame = new FrameLayout(this);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new MapBridge(), "AndroidPicker");
        mapFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView cross = text("+", 34f, 0xFFFFFFFF);
        cross.setGravity(Gravity.CENTER);
        cross.setShadowLayer(5f, 0f, 1f, 0xFF000000);
        FrameLayout.LayoutParams crossParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
        mapFrame.addView(cross, crossParams);
        LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mapParams.setMargins(0, dp(6), 0, dp(6));
        root.addView(mapFrame, mapParams);

        coordinateText = text("ยังไม่ได้เลือกจุด", 14f, 0xFFFFFFFF);
        coordinateText.setGravity(Gravity.CENTER);
        coordinateText.setPadding(dp(6), dp(4), dp(6), dp(2));
        root.addView(coordinateText);
        statusText = text("", 12f, 0xFF9FA2AA);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText);

        LinearLayout utility = new LinearLayout(this);
        PressFeedbackButton mine = button("◎ ตำแหน่งฉัน", 12f);
        mine.setOnClickListener(v -> goCurrentLocation());
        utility.addView(mine, new LinearLayout.LayoutParams(0, dp(44), 1f));
        PressFeedbackButton center = button("＋ เลือกจุดกลาง", 12f);
        center.setOnClickListener(v -> webView.evaluateJavascript("selectCenter()", null));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        cp.setMarginStart(dp(6));
        utility.addView(center, cp);
        PressFeedbackButton layer = button("▧ แผนที่/ดาวเทียม", 12f);
        layer.setOnClickListener(v -> webView.evaluateJavascript("toggleLayer()", null));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMarginStart(dp(6));
        utility.addView(layer, lp);
        root.addView(utility);

        PressFeedbackButton use = button("📍 ใช้ตำแหน่งนี้", 16f);
        use.setOnClickListener(v -> useSelection());
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        up.setMargins(0, dp(7), 0, 0);
        root.addView(use, up);

        setContentView(root);
    }

    private void loadMap(double lat, double lon, int zoom, boolean placeMarker) {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>"
                + "<style>html,body,#map{height:100%;margin:0;background:#111;} .leaflet-control-attribution{font-size:9px}</style>"
                + "</head><body><div id='map'></div><script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script><script>"
                + "var map=L.map('map',{zoomControl:true}).setView([__LAT__,__LON__],__ZOOM__);"
                + "var street=L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap contributors'});"
                + "var satellite=L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'Tiles © Esri'});"
                + "street.addTo(map); var sat=false; var marker=null;"
                + "function choose(lat,lng){if(marker){marker.setLatLng([lat,lng]);}else{marker=L.marker([lat,lng]).addTo(map);} AndroidPicker.onPick(String(lat),String(lng));}"
                + "map.on('click',function(e){choose(e.latlng.lat,e.latlng.lng);});"
                + "function selectCenter(){var c=map.getCenter();choose(c.lat,c.lng);}"
                + "function moveTo(lat,lng,z,mark){map.setView([lat,lng],z||16);if(mark){choose(lat,lng);}}"
                + "function toggleLayer(){if(sat){map.removeLayer(satellite);street.addTo(map);sat=false;}else{map.removeLayer(street);satellite.addTo(map);sat=true;}}"
                + (placeMarker ? "choose(__LAT__,__LON__);" : "")
                + "</script></body></html>";
        html = html.replace("__LAT__", String.format(Locale.US, "%.7f", lat))
                .replace("__LON__", String.format(Locale.US, "%.7f", lon))
                .replace("__ZOOM__", Integer.toString(zoom));
        webView.loadDataWithBaseURL("https://localhost/", html, "text/html", "UTF-8", null);
    }

    private void searchPlace() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) {
            searchInput.setError("กรุณาพิมพ์ชื่อสถานที่");
            return;
        }
        statusText.setText("กำลังค้นหา “" + q + "”...");
        new Thread(() -> {
            Address best = null;
            String err = null;
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocationName(q, 1);
                if (list != null && !list.isEmpty()) best = list.get(0);
            } catch (Exception e) {
                err = e.getMessage();
            }
            Address result = best;
            String error = err;
            runOnUiThread(() -> {
                if (result == null) {
                    statusText.setText("ค้นหาไม่พบ");
                    Toast.makeText(this, error == null ? "ไม่พบสถานที่" : error, Toast.LENGTH_LONG).show();
                    return;
                }
                double la = result.getLatitude();
                double lo = result.getLongitude();
                selectedLat = la;
                selectedLon = lo;
                updateCoordinate();
                webView.evaluateJavascript(String.format(Locale.US,
                        "moveTo(%.7f,%.7f,17,true)", la, lo), null);
                statusText.setText(addressText(result));
            });
        }, "map-picker-search").start();
    }

    private void goCurrentLocation() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            waitingPermission = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
            return;
        }
        statusText.setText("กำลังอ่านตำแหน่งปัจจุบัน...");
        LocationHelper helper = new LocationHelper(this);
        Location best = helper.getBestLocation();
        if (best != null) {
            moveToLocation(best);
            return;
        }
        helper.refresh(new LocationHelper.Callback() {
            private boolean done;
            @Override public void onLocation(@NonNull Location location) {
                if (done) return;
                done = true;
                runOnUiThread(() -> moveToLocation(location));
            }
            @Override public void onUnavailable(@NonNull String reason) {
                if (done) return;
                done = true;
                runOnUiThread(() -> statusText.setText("ตำแหน่งไม่พร้อม: " + reason));
            }
        });
    }

    private void moveToLocation(Location location) {
        selectedLat = location.getLatitude();
        selectedLon = location.getLongitude();
        updateCoordinate();
        webView.evaluateJavascript(String.format(Locale.US,
                "moveTo(%.7f,%.7f,17,true)", selectedLat, selectedLon), null);
        statusText.setText("ตำแหน่งจากเครื่อง");
    }

    private void useSelection() {
        if (Double.isNaN(selectedLat) || Double.isNaN(selectedLon)) {
            Toast.makeText(this, "กรุณาแตะเลือกจุดบนแผนที่ก่อน", Toast.LENGTH_SHORT).show();
            return;
        }
        statusText.setText("กำลังอ่านชื่อสถานที่...");
        new Thread(() -> {
            String label = "ตำแหน่งที่เลือกจากแผนที่";
            try {
                Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                List<Address> list = geocoder.getFromLocation(selectedLat, selectedLon, 1);
                if (list != null && !list.isEmpty()) label = addressText(list.get(0));
            } catch (Exception ignored) {}
            String finalLabel = label;
            runOnUiThread(() -> {
                Intent data = new Intent();
                data.putExtra(EXTRA_LAT, selectedLat);
                data.putExtra(EXTRA_LON, selectedLon);
                data.putExtra(EXTRA_LABEL, finalLabel);
                setResult(RESULT_OK, data);
                finish();
            });
        }, "map-picker-reverse-geocode").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_LOCATION || !waitingPermission) return;
        waitingPermission = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                goCurrentLocation();
                return;
            }
        }
        Toast.makeText(this, "ต้องอนุญาตตำแหน่งก่อนใช้ตำแหน่งปัจจุบัน", Toast.LENGTH_LONG).show();
    }

    private String addressText(Address a) {
        if (a.getMaxAddressLineIndex() >= 0 && a.getAddressLine(0) != null) return a.getAddressLine(0);
        if (a.getFeatureName() != null) return a.getFeatureName();
        if (a.getLocality() != null) return a.getLocality();
        return "ตำแหน่งที่เลือกจากแผนที่";
    }

    private void updateCoordinate() {
        coordinateText.setText(String.format(Locale.US, "📍 %.6f, %.6f", selectedLat, selectedLon));
    }

    private final class MapBridge {
        @JavascriptInterface
        public void onPick(String lat, String lon) {
            try {
                double la = Double.parseDouble(lat);
                double lo = Double.parseDouble(lon);
                runOnUiThread(() -> {
                    selectedLat = la;
                    selectedLon = lo;
                    updateCoordinate();
                    statusText.setText("เลือกจุดแล้ว • กด “ใช้ตำแหน่งนี้” เมื่อตรงตำแหน่ง");
                });
            } catch (Exception ignored) {}
        }
    }

    private PressFeedbackButton button(String text, float size) {
        PressFeedbackButton b = new PressFeedbackButton(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(size);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackgroundResource(R.drawable.bg_button_dark);
        b.setBackgroundTintList(null);
        b.setStateListAnimator(null);
        return b;
    }

    private TextView text(String text, float size, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}