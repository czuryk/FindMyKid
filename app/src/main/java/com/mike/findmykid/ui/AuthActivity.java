package com.mike.findmykid.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.mike.findmykid.R;
import com.mike.findmykid.data.SettingsManager;
import com.mike.findmykid.utils.CryptoUtils;

public class AuthActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_auth);

        EditText etPassword = findViewById(R.id.etPassword);
        Button btnLogin = findViewById(R.id.btnLogin);

        SettingsManager settingsManager = new SettingsManager(this);

        btnLogin.setOnClickListener(v -> {
            String pwd = etPassword.getText().toString();
            String hash = CryptoUtils.hashPassword(pwd);

            if (hash.equals(settingsManager.getMasterPasswordHash())) {
                if (getIntent().getBooleanExtra("from_settings", false)) {
                    setResult(RESULT_OK);
                    finish();
                } else {
                    startActivity(new Intent(this, SettingsActivity.class));
                    finish();
                }
            } else {
                Toast.makeText(this, "Invalid Password", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
