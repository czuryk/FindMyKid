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

public class PasswordSetupActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_password_setup);

        EditText etPassword = findViewById(R.id.etPassword);
        EditText etPasswordConfirm = findViewById(R.id.etPasswordConfirm);
        Button btnSave = findViewById(R.id.btnSave);

        btnSave.setOnClickListener(v -> {
            String pwd = etPassword.getText().toString();
            String confirm = etPasswordConfirm.getText().toString();

            if (pwd.length() < 4) {
                Toast.makeText(this, "Password too short", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!pwd.equals(confirm)) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }

            String hash = CryptoUtils.hashPassword(pwd);
            SettingsManager settingsManager = new SettingsManager(this);
            settingsManager.setMasterPasswordHash(hash);

            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        });
    }
}
