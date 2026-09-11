package com.example.voicechanger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 10;

    private VoiceEngine engine;
    private Spinner effectSpinner;
    private Button toggleButton;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        engine = new VoiceEngine(msg -> runOnUiThread(() ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()));

        effectSpinner = findViewById(R.id.effectSpinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.effects, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        effectSpinner.setAdapter(adapter);
        effectSpinner.setSelection(VoiceEngine.EFFECT_DEEP);

        toggleButton = findViewById(R.id.toggleButton);
        statusText = findViewById(R.id.statusText);

        toggleButton.setOnClickListener(v -> {
            if (engine.isRunning()) stopEngine();
            else startEngine();
        });
    }

    private void startEngine() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        engine.setEffect(effectSpinner.getSelectedItemPosition());
        engine.start();
        updateUi();
    }

    private void stopEngine() {
        engine.stop();
        updateUi();
    }

    private void updateUi() {
        boolean on = engine.isRunning();
        toggleButton.setText(on ? R.string.stop : R.string.start);
        statusText.setText(on ? R.string.status_on : R.string.status_off);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startEngine();
        } else {
            Toast.makeText(this, R.string.mic_denied, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        if (engine.isRunning()) stopEngine();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        engine.stop();
        super.onDestroy();
    }
  }
