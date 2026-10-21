package com.darsha.steps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Store store = Store.get(context);
        if (!store.reminderOn()) return;
        NotificationHelper.createChannels(context);
        NotificationHelper.reminder(context);
    }
}
