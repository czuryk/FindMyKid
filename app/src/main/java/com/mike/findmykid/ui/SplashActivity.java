package com.mike.findmykid.ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.mike.findmykid.data.SettingsManager;

public class SplashActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        SettingsManager settingsManager = new SettingsManager(this);
        if (settingsManager.isPasswordSet()) {
            startActivity(new Intent(this, AuthActivity.class));
        } else {
            startActivity(new Intent(this, PasswordSetupActivity.class));
        }
        finish();
    }
}
