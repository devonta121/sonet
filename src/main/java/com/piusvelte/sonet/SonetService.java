/*
 * Sonet - Android Social Networking Widget
 * Copyright (C) 2009 Bryan Emmanuel
 * 
 * This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *  
 *  Bryan Emmanuel piusvelte@gmail.com
 */
package com.piusvelte.sonet;

import static com.piusvelte.sonet.Sonet.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import mobi.intuitit.android.content.LauncherIntent;
import mobi.intuitit.android.widget.BoundRemoteViews;

import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.message.BasicNameValuePair;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.piusvelte.eidos.Eidos;
import com.piusvelte.sonet.Sonet.Entities;
import com.piusvelte.sonet.Sonet.Notifications;
import com.piusvelte.sonet.Sonet.Statuses;
import com.piusvelte.sonet.Sonet.Statuses_styles;
import com.piusvelte.sonet.Sonet.Widget_accounts;
import com.piusvelte.sonet.Sonet.Widget_accounts_view;
import com.piusvelte.sonet.Sonet.Widgets;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.RemoteViews;

public class SonetService extends Service {
	private static final String TAG = "SonetService";
	private final static HashMap<Integer, AsyncTask<Integer, String, Integer>> mStatusesLoaders = new HashMap<Integer, AsyncTask<Integer, String, Integer>>();
	private final ArrayList<AsyncTask<SmsMessage, String, int[]>> mSMSLoaders = new ArrayList<AsyncTask<SmsMessage, String, int[]>>();
	private AlarmManager mAlarmManager;
	private ConnectivityManager mConnectivityManager;
	private SonetCrypto mSonetCrypto;
	private String mNotify = null;
	private SimpleDateFormat mSimpleDateFormat = null;

	private static Method sSetRemoteAdapter;
	private static Method sSetPendingIntentTemplate;
	private static Method sSetEmptyView;
	private static Method sNotifyAppWidgetViewDataChanged;
	private static boolean sNativeScrollingSupported = false;

	private int mStartId = Sonet.INVALID_SERVICE;

	static {
		if (Integer.valueOf(android.os.Build.VERSION.SDK) >= 11) {
			try {
				sSetEmptyView = RemoteViews.class.getMethod("setEmptyView", new Class[]{int.class, int.class});
				sSetPendingIntentTemplate = RemoteViews.class.getMethod("setPendingIntentTemplate", new Class[]{int.class, PendingIntent.class});
				sSetRemoteAdapter = RemoteViews.class.getMethod("setRemoteAdapter", new Class[]{int.class, int.class, Intent.class});
				sNotifyAppWidgetViewDataChanged = AppWidgetManager.class.getMethod("notifyAppWidgetViewDataChanged", new Class[]{int.class, int.class});
				sNativeScrollingSupported = true;
			} catch (NoSuchMethodException nsme) {
				Log.d(TAG, "native scrolling not supported: " + nsme.toString());
			}
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		// handle version changes
		int currVer = 0;
		try {
			currVer = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
		} catch (NameNotFoundException e) {
			e.printStackTrace();
		}
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		if (!sp.contains(getString(R.string.key_version)) || (currVer > sp.getInt(getString(R.string.key_version), 0))) {
			sp.edit().putInt(getString(R.string.key_version), currVer).commit();
            Eidos.requestBackup(this);
		}
		mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
		mConnectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		mSonetCrypto = SonetCrypto.getInstance(getApplicationContext());
		// check the instant upload settings
		startService(Sonet.getPackageIntent(getApplicationContext(), SonetUploader.class));
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		mStartId = startId;
		start(intent);
		return START_REDELIVER_INTENT;
	}

	@Override
	public void onStart(Intent intent, int startId) {
		mStartId = startId;
		start(intent);
	}

	private void start(Intent intent) {
		if (intent != null) {
			String action = intent.getAction();
			Log.d(TAG,"action:" + action);
			if (ACTION_REFRESH.equals(action)) {
				if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS))
					putValidatedUpdates(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS), 1);
				else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID))
					putValidatedUpdates(new int[]{intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)}, 1);
				else if (intent.getData() != null)
					putValidatedUpdates(new int[]{Integer.parseInt(intent.getData().getLastPathSegment())}, 1);
				else
					putValidatedUpdates(null, 0);
			} else if (LauncherIntent.Action.ACTION_READY.equals(action)) {
				if (intent.hasExtra(EXTRA_SCROLLABLE_VERSION) && intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					int scrollableVersion = intent.getIntExtra(EXTRA_SCROLLABLE_VERSION, 1);
					int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
					// check if the scrollable needs to be built
					Cursor widget = this.getContentResolver().query(Widgets.getContentUri(SonetService.this), new String[]{Widgets._ID, Widgets.SCROLLABLE}, Widgets.WIDGET + "=?", new String[]{Integer.toString(appWidgetId)}, null);
					if (widget.moveToFirst()) {
						if (widget.getInt(widget.getColumnIndex(Widgets.SCROLLABLE)) < scrollableVersion) {
							ContentValues values = new ContentValues();
							values.put(Widgets.SCROLLABLE, scrollableVersion);
							// set the scrollable version
							this.getContentResolver().update(Widgets.getContentUri(SonetService.this), values, Widgets.WIDGET + "=?", new String[] {Integer.toString(appWidgetId)});
							putValidatedUpdates(new int[]{appWidgetId}, 1);
						} else
							putValidatedUpdates(new int[]{appWidgetId}, 1);
					} else {
						ContentValues values = new ContentValues();
						values.put(Widgets.SCROLLABLE, scrollableVersion);
						// set the scrollable version
						this.getContentResolver().update(Widgets.getContentUri(SonetService.this), values, Widgets.WIDGET + "=?", new String[] {Integer.toString(appWidgetId)});
						putValidatedUpdates(new int[]{appWidgetId}, 1);
					}
					widget.close();
				} else if (intent.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID)) {
					// requery
					putValidatedUpdates(new int[]{intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)}, 0);
				}
			} else if (SMS_RECEIVED.equals(action)) {
				// parse the sms, and notify any widgets which have sms enabled
				Bundle bundle = intent.getExtras();
				Object[] pdus = (Object[]) bundle.get("pdus");
				for (int i = 0; i < pdus.length; i++) {
					SmsMessage msg = SmsMessage.createFromPdu((byte[]) pdus[i]);
					AsyncTask<SmsMessage, String, int[]> smsLoader = new AsyncTask<SmsMessage, String, int[]>() {

						@Override
						protected int[] doInBackground(SmsMessage... msg) {
							// check if SMS is enabled anywhere
							Cursor widgets = getContentResolver().query(Widget_accounts_view.getContentUri(SonetService.this), new String[]{Widget_accounts_view._ID, Widget_accounts_view.WIDGET, Widget_accounts_view.ACCOUNT}, Widget_accounts_view.SERVICE + "=?", new String[]{Integer.toString(SMS)}, null);
							int[] appWidgetIds = new int[widgets.getCount()];
							if (widgets.moveToFirst()) {
								// insert this message to the statuses db and requery scrollable/rebuild widget
								// check if this is a contact
								String phone = msg[0].getOriginatingAddress();
								String friend = phone;
								byte[] profile = null;
								Uri content_uri = null;
								// unknown numbers crash here in the emulator
								Cursor phones = getContentResolver().query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phone)), new String[]{ContactsContract.PhoneLookup._ID}, null, null, null);
								if (phones.moveToFirst())
									content_uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, phones.getLong(0));
								else {
									Cursor emails = getContentResolver().query(Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(phone)), new String[]{ContactsContract.CommonDataKinds.Email._ID}, null, null, null);
									if (emails.moveToFirst())
										content_uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, emails.getLong(0));
									emails.close();
								}
								phones.close();
								if (content_uri != null) {
									// load contact
									Cursor contacts = getContentResolver().query(content_uri, new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null);
									if (contacts.moveToFirst())
										friend = contacts.getString(0);
									contacts.close();
									profile = getBlob(ContactsContract.Contacts.openContactPhotoInputStream(getContentResolver(), content_uri));
								}
								long accountId = widgets.getLong(2);
								long id;
								ContentValues values = new ContentValues();
								values.put(Entities.ESID, phone);
								values.put(Entities.FRIEND, friend);
								values.put(Entities.PROFILE, profile);
								values.put(Entities.ACCOUNT, accountId);
								Cursor entity = getContentResolver().query(Entities.getContentUri(SonetService.this), new String[]{Entities._ID}, Entities.ACCOUNT + "=? and " + Entities.ESID + "=?", new String[]{Long.toString(accountId), mSonetCrypto.Encrypt(phone)}, null);
								if (entity.moveToFirst()) {
									id = entity.getLong(0);
									getContentResolver().update(Entities.getContentUri(SonetService.this), values, Entities._ID + "=?", new String[]{Long.toString(id)});
								} else
									id = Long.parseLong(getContentResolver().insert(Entities.getContentUri(SonetService.this), values).getLastPathSegment());
								entity.close();
								values.clear();
								Long created = msg[0].getTimestampMillis();
								values.put(Statuses.CREATED, created);
								values.put(Statuses.ENTITY, id);
								values.put(Statuses.MESSAGE, msg[0].getMessageBody());
								values.put(Statuses.SERVICE, SMS);
								while (!widgets.isAfterLast()) {
									int widget = widgets.getInt(1);
									appWidgetIds[widgets.getPosition()] = widget;
									// get settings
									boolean time24hr = true;
									int status_bg_color = Sonet.default_message_bg_color;
									int profile_bg_color = Sonet.default_message_bg_color;
									int friend_bg_color = Sonet.default_friend_bg_color;
									boolean icon = true;
									int status_count = Sonet.default_statuses_per_account;
									int notifications = 0;
									Cursor c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(widget), Long.toString(accountId)}, null);
									if (!c.moveToFirst()) {
										c.close();
										c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(widget), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
										if (!c.moveToFirst()) {
											c.close();
											c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(AppWidgetManager.INVALID_APPWIDGET_ID), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
											if (!c.moveToFirst())
												initAccountSettings(SonetService.this, AppWidgetManager.INVALID_APPWIDGET_ID, Sonet.INVALID_ACCOUNT_ID);
											if (widget != AppWidgetManager.INVALID_APPWIDGET_ID)
												initAccountSettings(SonetService.this, widget, Sonet.INVALID_ACCOUNT_ID);
										}
										initAccountSettings(SonetService.this, widget, accountId);
									}
									if (c.moveToFirst()) {
										time24hr = c.getInt(0) == 1;
										status_bg_color = c.getInt(1);
										icon = c.getInt(2) == 1;
										status_count = c.getInt(3);
										if (c.getInt(4) == 1)
											notifications |= Notification.DEFAULT_SOUND;
										if (c.getInt(5) == 1)
											notifications |= Notification.DEFAULT_VIBRATE;
										if (c.getInt(6) == 1)
											notifications |= Notification.DEFAULT_LIGHTS;
										profile_bg_color = c.getInt(7);
										friend_bg_color = c.getInt(8);
									}
									c.close();
									values.put(Statuses.CREATEDTEXT, Sonet.getCreatedText(created, time24hr));
									// update the bg and icon
									// create the status_bg
									values.put(Statuses.STATUS_BG, createBackground(status_bg_color));
									// friend_bg
									values.put(Statuses.FRIEND_BG, createBackground(friend_bg_color));
									// profile_bg
									values.put(Statuses.PROFILE_BG, createBackground(profile_bg_color));
									values.put(Statuses.ICON, icon ? getBlob(getResources(), map_icons[SMS]) : null);
									// insert the message
									values.put(Statuses.WIDGET, widget);
									values.put(Statuses.ACCOUNT, accountId);
									getContentResolver().insert(Statuses.getContentUri(SonetService.this), values);
									// check the status count, removing old sms
									Cursor statuses = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID}, Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{Integer.toString(widget), Long.toString(accountId)}, Statuses.CREATED + " desc");
									if (statuses.moveToFirst()) {
										while (!statuses.isAfterLast()) {
											if (statuses.getPosition() >= status_count) {
												getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses._ID + "=?", new String[]{Long.toString(statuses.getLong(statuses.getColumnIndex(Statuses._ID)))});
											}
											statuses.moveToNext();
										}
									}
									statuses.close();
									if (notifications != 0)
										publishProgress(Integer.toString(notifications), friend + " sent a message");
									widgets.moveToNext();
								}
							}
							widgets.close();
							return appWidgetIds;
						}

						@Override
						protected void onProgressUpdate(String... updates) {
							int notifications = Integer.parseInt(updates[0]);
							if (notifications != 0) {
								Notification notification = new Notification(R.drawable.notification, updates[1], System.currentTimeMillis());
								notification.setLatestEventInfo(getBaseContext(), "New messages", updates[1], PendingIntent.getActivity(SonetService.this, 0, (Sonet.getPackageIntent(SonetService.this, SonetNotifications.class)), 0));
								notification.defaults |= notifications;
								((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFY_ID, notification);
							}
						}

						@Override
						protected void onPostExecute(int[] appWidgetIds) {
							// remove self from thread list
							if (!mSMSLoaders.isEmpty())
								mSMSLoaders.remove(this);
							putValidatedUpdates(appWidgetIds, 0);
						}

					};
					mSMSLoaders.add(smsLoader);
					smsLoader.execute(msg);
				}
			} else if (ACTION_PAGE_DOWN.equals(action))
				(new PagingTask()).execute(Integer.parseInt(intent.getData().getLastPathSegment()), intent.getIntExtra(ACTION_PAGE_DOWN, 0));
			else if (ACTION_PAGE_UP.equals(action))
				(new PagingTask()).execute(Integer.parseInt(intent.getData().getLastPathSegment()), intent.getIntExtra(ACTION_PAGE_UP, 0));
			else {
				// this might be a widget update from the widget refresh button
				int appWidgetId;
				try {
					appWidgetId = Integer.parseInt(action);
					putValidatedUpdates(new int[]{appWidgetId}, 1);
				} catch (NumberFormatException e) {
					Log.d(TAG,"unknown action:" + action);
				}
			}
		}
	}

	class PagingTask extends AsyncTask<Integer, Void, Void> {

		@Override
		protected Void doInBackground(Integer... arg0) {
			boolean display_profile = true;			
			boolean hasbuttons = false;
			int scrollable = 0;
			int buttons_bg_color = Sonet.default_buttons_bg_color;
			int buttons_color = Sonet.default_buttons_color;
			int buttons_textsize = Sonet.default_buttons_textsize;			
			int margin = Sonet.default_margin;
			Cursor settings = getSettingsCursor(arg0[0]);
			if (settings.moveToFirst()) {
				hasbuttons = settings.getInt(0) == 1;
				buttons_color = settings.getInt(1);
				buttons_bg_color = settings.getInt(2);
				buttons_textsize = settings.getInt(3);
				scrollable = settings.getInt(4);
				display_profile = settings.getInt(5) == 1;
				margin = settings.getInt(6);
			}
			settings.close();
			// rebuild the widget, using the paging criteria passed in
			buildWidgetButtons(arg0[0], true, arg0[1], hasbuttons, scrollable, buttons_bg_color, buttons_color, buttons_textsize, display_profile, margin);
			return null;
		}

	}

	protected void putValidatedUpdates(int[] appWidgetIds, int reload) {
		int[] awi = Sonet.getWidgets(getApplicationContext(), AppWidgetManager.getInstance(getApplicationContext()));
		if ((appWidgetIds != null) && (appWidgetIds.length > 0)) {
			// check for phantom widgets
			for (int appWidgetId : appWidgetIds) {
				// About.java will send an invalid appwidget id
				if ((appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) || Sonet.arrayContains(awi, appWidgetId)) {
					putNewUpdate(appWidgetId, reload);
				} else {
					// remove phantom widgets
					getContentResolver().delete(Widgets.getContentUri(SonetService.this), Widgets.WIDGET + "=?", new String[] { Integer.toString(appWidgetId) });
					getContentResolver().delete(Widget_accounts.getContentUri(SonetService.this), Widget_accounts.WIDGET + "=?", new String[] { Integer.toString(appWidgetId) });
					getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=?", new String[] { Integer.toString(appWidgetId) });
				}
			}
		} else if ((awi != null) && (awi.length > 0)) {
			for (int appWidgetId : awi) {
				putNewUpdate(appWidgetId, reload);
			}
		}
	}

	protected void putNewUpdate(int widget, int reload) {
		// if the widget is already loading, don't load another
		if (mStatusesLoaders.isEmpty() || !mStatusesLoaders.containsKey(widget) || ((reload == 1) && (mStatusesLoaders.get(widget).cancel(true)))) {
			StatusesLoader loader = new StatusesLoader();
			mStatusesLoaders.put(widget, loader);
			loader.execute(widget, reload);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onDestroy() {
		if (!mStatusesLoaders.isEmpty()) {
			Iterator<AsyncTask<Integer, String, Integer>> itr = mStatusesLoaders.values().iterator();
			while (itr.hasNext()) {
				AsyncTask<Integer, String, Integer> statusesLoader = itr.next();
				statusesLoader.cancel(true);
			}
			mStatusesLoaders.clear();
		}
		if (!mSMSLoaders.isEmpty()) {
			Iterator<AsyncTask<SmsMessage, String, int[]>> itr = mSMSLoaders.iterator();
			while (itr.hasNext()) {
				AsyncTask<SmsMessage, String, int[]> smsLoader = itr.next();
				smsLoader.cancel(true);
			}
			mSMSLoaders.clear();
		}
		super.onDestroy();
	}

	private Cursor getSettingsCursor(int appWidgetId) {
		Cursor settings = getContentResolver().query(Widgets_settings.getContentUri(this), new String[]{Widgets.HASBUTTONS, Widgets.BUTTONS_COLOR, Widgets.BUTTONS_BG_COLOR, Widgets.BUTTONS_TEXTSIZE, Widgets.SCROLLABLE, Widgets.DISPLAY_PROFILE, Widgets.MARGIN, Widgets.INTERVAL, Widgets.BACKGROUND_UPDATE}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(appWidgetId), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
		if (!settings.moveToFirst()) {
			settings.close();
			settings = getContentResolver().query(Widgets_settings.getContentUri(this), new String[]{Widgets.HASBUTTONS, Widgets.BUTTONS_COLOR, Widgets.BUTTONS_BG_COLOR, Widgets.BUTTONS_TEXTSIZE, Widgets.SCROLLABLE, Widgets.DISPLAY_PROFILE, Widgets.MARGIN, Widgets.INTERVAL, Widgets.BACKGROUND_UPDATE}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(AppWidgetManager.INVALID_APPWIDGET_ID), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
			if (!settings.moveToFirst())
				initAccountSettings(this, AppWidgetManager.INVALID_APPWIDGET_ID, Sonet.INVALID_ACCOUNT_ID);
			// don't insert a duplicate row
			if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
				initAccountSettings(this, appWidgetId, Sonet.INVALID_ACCOUNT_ID);
		}
		return settings;
	}

	class StatusesLoader extends AsyncTask<Integer, String, Integer> {

		@Override
		protected Integer doInBackground(Integer... params) {
			// first handle deletes, then scroll updates, finally regular updates
			final int appWidgetId = params[0];
			final String widget = Integer.toString(appWidgetId);
			final boolean reload = params[1] != 0;
			Log.d(TAG,"StatusesLoader;widget:"+widget+",reload:"+reload);
			int refreshInterval = Sonet.default_interval;
			boolean backgroundUpdate = true;
			boolean display_profile = true;			
			boolean hasbuttons = false;
			int scrollable = 0;
			int buttons_bg_color = Sonet.default_buttons_bg_color;
			int buttons_color = Sonet.default_buttons_color;
			int buttons_textsize = Sonet.default_buttons_textsize;			
			int margin = Sonet.default_margin;
			Cursor settings = getSettingsCursor(appWidgetId);
			if (settings.moveToFirst()) {
				hasbuttons = settings.getInt(0) == 1;
				buttons_color = settings.getInt(1);
				buttons_bg_color = settings.getInt(2);
				buttons_textsize = settings.getInt(3);
				scrollable = settings.getInt(4);
				display_profile = settings.getInt(5) == 1;
				margin = settings.getInt(6);
				refreshInterval = settings.getInt(7);
				backgroundUpdate = settings.getInt(8) == 1;
			}
			settings.close();
			// the widget will start out as the default widget.xml, which simply says "loading..."
			// if there's a cache, that should be quickly reloaded while new updates come down
			// otherwise, replace the widget with "loading..."
			// clear the messages
			getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widget, Long.toString(Sonet.INVALID_ACCOUNT_ID)});
			Cursor statuses = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID}, Statuses.WIDGET + "=?", new String[]{widget}, null);
			boolean hasCache = statuses.moveToFirst();
			statuses.close();
			// the alarm should always be set, rather than depend on the tasks to complete
			//			Log.d(TAG,"awi:"+appWidgetId+",hasCache:"+hasCache+",reload:"+reload+",refreshInterval:"+refreshInterval);
			if ((appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) && (!hasCache || reload) && (refreshInterval > 0)) {
				mAlarmManager.cancel(PendingIntent.getService(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetService.class).setAction(widget), 0));
				mAlarmManager.set(backgroundUpdate ? AlarmManager.RTC_WAKEUP : AlarmManager.RTC, System.currentTimeMillis() + refreshInterval, PendingIntent.getService(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetService.class).setData(Uri.withAppendedPath(Widgets.getContentUri(SonetService.this), widget)).setAction(ACTION_REFRESH), 0));
				//				Log.d(TAG,"alarm set");
			}
			// get the accounts
			Cursor accounts = getContentResolver().query(Widget_accounts_view.getContentUri(SonetService.this), new String[]{Widget_accounts_view.ACCOUNT, Widget_accounts_view.TOKEN, Widget_accounts_view.SECRET, Widget_accounts_view.SERVICE, Widget_accounts_view.SID}, Widget_accounts_view.WIDGET + "=?", new String[]{widget}, null);
			if (hasCache && accounts.moveToFirst()) {
				//				Log.d(TAG,"update cache styles");
				// update the styles for existing statuses while fetching new statuses
				while (!accounts.isAfterLast()) {
					long account = accounts.getLong(0);
					int service = accounts.getInt(3);
					int status_bg_color = Sonet.default_message_bg_color;
					int profile_bg_color = Sonet.default_message_bg_color;
					int friend_bg_color = Sonet.default_friend_bg_color;
					boolean icon = true;
					Cursor c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{widget, Long.toString(account)}, null);
					if (!c.moveToFirst()) {
						// no account settings
						c.close();
						c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{widget, Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
						if (!c.moveToFirst()) {
							// no widget settings
							c.close();
							c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(AppWidgetManager.INVALID_APPWIDGET_ID), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
							if (!c.moveToFirst())
								initAccountSettings(SonetService.this, AppWidgetManager.INVALID_APPWIDGET_ID, Sonet.INVALID_ACCOUNT_ID);
							// don't insert a duplicate row
							if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
								initAccountSettings(SonetService.this, appWidgetId, Sonet.INVALID_ACCOUNT_ID);
						}
						initAccountSettings(SonetService.this, appWidgetId, account);
					}
					if (c.moveToFirst()) {
						status_bg_color = c.getInt(0);
						icon = c.getInt(1) == 1;
						profile_bg_color = c.getInt(2);
						friend_bg_color = c.getInt(3);
					}
					c.close();
					// update the bg and icon
					// create the status_bg
					ContentValues values = new ContentValues();
					values.put(Statuses.STATUS_BG, createBackground(status_bg_color));
					// friend_bg
					values.put(Statuses.FRIEND_BG, createBackground(friend_bg_color));
					// profile_bg
					values.put(Statuses.PROFILE_BG, createBackground(profile_bg_color));
					// icon
					values.put(Statuses.ICON, icon ? getBlob(getResources(), map_icons[service]) : null);
					getContentResolver().update(Statuses.getContentUri(SonetService.this), values, Statuses.WIDGET + "=? and " + Statuses.SERVICE + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widget, Integer.toString(service), Long.toString(account)});
					accounts.moveToNext();
				}
			} else {
				// if no cache inform the user that the widget is loading
				addStatusItem(widget, getString(R.string.updating), appWidgetId);
			}
			// loading takes time, so don't leave an empty widget sitting there
			if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
				// build the widget
				//				Log.d(TAG,"temp widget build");
				buildWidgetButtons(appWidgetId, false, 0, hasbuttons, scrollable, buttons_bg_color, buttons_color, buttons_textsize, display_profile, margin);
			} else {
				// update the About.java for in-app viewing
				//				Log.d(TAG,"temp About build");
				getContentResolver().notifyChange(Statuses_styles.getContentUri(SonetService.this), null);
			}
			if (accounts.moveToFirst()) {
				// only reload if the token's can be decrypted and if there's no cache or a reload is requested
				if (!hasCache || reload) {
					mNotify = null;
					int notifications = 0;
					// load the updates
					while (!accounts.isAfterLast()) {
						HttpClient httpClient = SonetHttpClient.getThreadSafeClient(getApplicationContext());
						long account = accounts.getLong(0);
						int service = accounts.getInt(3);
						//						Log.d(TAG,"widget:"+widget+",account:"+account+",service:"+service);
						String token = mSonetCrypto.Decrypt(accounts.getString(1));
						String secret = mSonetCrypto.Decrypt(accounts.getString(2));
						String accountEsid = mSonetCrypto.Decrypt(accounts.getString(4));
						// get the settings form time24hr and bg_color
						boolean time24hr = false;
						int status_bg_color = Sonet.default_message_bg_color;
						int profile_bg_color = Sonet.default_message_bg_color;
						int friend_bg_color = Sonet.default_friend_bg_color;
						boolean icon = true;
						int status_count = Sonet.default_statuses_per_account;
						Cursor c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{widget, Long.toString(account)}, null);
						if (!c.moveToFirst()) {
							// no account settings
							c.close();
							c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{widget, Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
							if (!c.moveToFirst()) {
								// no widget settings
								c.close();
								c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(AppWidgetManager.INVALID_APPWIDGET_ID), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
								if (!c.moveToFirst())
									initAccountSettings(SonetService.this, AppWidgetManager.INVALID_APPWIDGET_ID, Sonet.INVALID_ACCOUNT_ID);
								if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
									initAccountSettings(SonetService.this, appWidgetId, Sonet.INVALID_ACCOUNT_ID);
							}
							initAccountSettings(SonetService.this, appWidgetId, account);
						}
						if (c.moveToFirst()) {
							time24hr = c.getInt(0) == 1;
							status_bg_color = c.getInt(1);
							icon = c.getInt(2) == 1;
							status_count = c.getInt(3);
							if (c.getInt(4) == 1)
								notifications |= Notification.DEFAULT_SOUND;
							if (c.getInt(5) == 1)
								notifications |= Notification.DEFAULT_VIBRATE;
							if (c.getInt(6) == 1)
								notifications |= Notification.DEFAULT_LIGHTS;
							profile_bg_color = c.getInt(7);
							friend_bg_color = c.getInt(8);
						}
						c.close();
						// if no connection, only update the status_bg and icons
						if ((mConnectivityManager.getActiveNetworkInfo() != null) && mConnectivityManager.getActiveNetworkInfo().isConnected()) {
							// get this account's statuses
							switch (service) {
							case TWITTER:
								updateTwitter(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case FACEBOOK:
								updateFacebook(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case MYSPACE:
								updateMySpace(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case FOURSQUARE:
								updateFoursquare(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case LINKEDIN:
								updateLinkedIn(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case RSS:
								updateRSS(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case IDENTICA:
								updateIdentiCa(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case GOOGLEPLUS:
								updateGooglePlus(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case PINTEREST:
								updatePinterest(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
								break;
							case CHATTER:
								updateChatter(token, secret, accountEsid, appWidgetId, widget, account, service, status_count, time24hr, display_profile, notifications, httpClient);
							}
							// remove old notifications
							getContentResolver().delete(Notifications.getContentUri(SonetService.this), Notifications.CLEARED + "=1 and " + Notifications.ACCOUNT + "=? and " + Notifications.CREATED + "<?", new String[]{Long.toString(account), Long.toString(System.currentTimeMillis() - 86400000)});
						} else {
							// no network connection
							if (hasCache) {
								// update created text
								updateCreatedText(widget, Long.toString(account), time24hr);
							} else {
								// clear the "loading" message and display "no connection"
								getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=?", new String[]{widget});
								addStatusItem(widget, getString(R.string.no_connection), appWidgetId);
							}
						}
						// update the bg and icon
						// create the status_bg
						ContentValues values = new ContentValues();
						values.put(Statuses.STATUS_BG, createBackground(status_bg_color));
						// friend_bg
						values.put(Statuses.FRIEND_BG, createBackground(friend_bg_color));
						// profile_bg
						values.put(Statuses.PROFILE_BG, createBackground(profile_bg_color));
						// icon
						values.put(Statuses.ICON, icon ? getBlob(getResources(), map_icons[service]) : null);
						getContentResolver().update(Statuses.getContentUri(SonetService.this), values, Statuses.WIDGET + "=? and " + Statuses.SERVICE + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widget, Integer.toString(service), Long.toString(account)});
						accounts.moveToNext();
					}
					if ((notifications != 0) && (mNotify != null)) {
						publishProgress(Integer.toString(notifications));
					}
				}
				// delete the existing loading and informational messages
				getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widget, Long.toString(Sonet.INVALID_ACCOUNT_ID)});
				// check statuses again
				Cursor statusesCheck = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID}, Statuses.WIDGET + "=?", new String[]{widget}, null);
				hasCache = statusesCheck.moveToFirst();
				statusesCheck.close();
				if (!hasCache) {
					// there should be a loading message displaying
					// if no updates have been loaded, display "no updates"
					addStatusItem(widget, getString(R.string.no_updates), appWidgetId);
				}
			} else {
				//				Log.d(TAG,"no accounts");
				// no accounts, clear cache
				getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=?", new String[]{widget});
				// insert no accounts message
				addStatusItem(widget, getString(R.string.no_accounts), appWidgetId);
			}
			accounts.close();
			// always update buttons, if !scrollable update widget both times, otherwise build scrollable first, requery second
			// see if the tasks are finished
			// non-scrollable widgets will be completely rebuilt, while scrollable widgets while be notified to requery
			if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
				//				Log.d(TAG,"full widget build");
				buildWidgetButtons(appWidgetId, true, 0, hasbuttons, scrollable, buttons_bg_color, buttons_color, buttons_textsize, display_profile, margin);
			} else {
				//				Log.d(TAG,"full About build");
				// notify change to About.java
				getContentResolver().notifyChange(Statuses_styles.getContentUri(SonetService.this), null);
			}
			return appWidgetId;
		}

		@Override
		protected void onCancelled(Integer appWidgetId) {
			//			Log.d(TAG,"loader cancelled");
		}

		@Override
		protected void onProgressUpdate(String... updates) {
			int notifications = Integer.parseInt(updates[0]);
			if (notifications != 0) {
				Notification notification = new Notification(R.drawable.notification, mNotify, System.currentTimeMillis());
				notification.setLatestEventInfo(getBaseContext(), "New messages", mNotify, PendingIntent.getActivity(SonetService.this, 0, (Sonet.getPackageIntent(SonetService.this, SonetNotifications.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP), 0));
				notification.defaults |= notifications;
				((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFY_ID, notification);
			}
		}

		@Override
		protected void onPostExecute(Integer appWidgetId) {
			// remove self from thread list
			if (!mStatusesLoaders.isEmpty() && mStatusesLoaders.containsKey(appWidgetId)) {
				mStatusesLoaders.remove(appWidgetId);
			}
			//			Log.d(TAG,"finished update, check queue");
			if (mStatusesLoaders.isEmpty()) {
				//				Log.d(TAG,"stop service");
				Sonet.release();
				stopSelfResult(mStartId);
			}
		}

		private void removeOldStatuses(String widgetId, String accountId) {
			Cursor statuses = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID}, Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widgetId, accountId}, null);
			if (statuses.moveToFirst()) {
				while (!statuses.isAfterLast()) {
					String id = Long.toString(statuses.getLong(0));
					getContentResolver().delete(Status_links.getContentUri(SonetService.this), Status_links.STATUS_ID + "=?", new String[]{id});
					getContentResolver().delete(Status_images.getContentUri(SonetService.this), Status_images.STATUS_ID + "=?", new String[]{id});
					statuses.moveToNext();
				}
			}
			statuses.close();
			getContentResolver().delete(Statuses.getContentUri(SonetService.this), Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widgetId, accountId});
			Cursor entities = getContentResolver().query(Entities.getContentUri(SonetService.this), new String[]{Entities._ID}, Entities.ACCOUNT + "=?", new String[]{accountId}, null);
			if (entities.moveToFirst()) {
				while (!entities.isAfterLast()) {
					Cursor s = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID}, Statuses.ACCOUNT + "=? and " + Statuses.WIDGET + " !=?", new String[]{accountId, widgetId}, null);
					if (!s.moveToFirst()) {
						// not in use, remove it
						getContentResolver().delete(Entities.getContentUri(SonetService.this), Entities._ID + "=?", new String[]{Long.toString(entities.getLong(0))});
					}
					s.close();
					entities.moveToNext();
				}
			}
			entities.close();
		}

		private void addStatusItem(String widget, String message, int appWidgetId) {
			int status_bg_color = Sonet.default_message_bg_color;
			int profile_bg_color = Sonet.default_message_bg_color;
			int friend_bg_color = Sonet.default_friend_bg_color;
			boolean icon = true;
			Cursor c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{widget, Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
			if (!c.moveToFirst()) {
				// no widget settings
				c.close();
				c = getContentResolver().query(Widgets_settings.getContentUri(SonetService.this), new String[]{Widgets.TIME24HR, Widgets.MESSAGES_BG_COLOR, Widgets.ICON, Widgets.STATUSES_PER_ACCOUNT, Widgets.SOUND, Widgets.VIBRATE, Widgets.LIGHTS, Widgets.PROFILES_BG_COLOR, Widgets.FRIEND_BG_COLOR}, Widgets.WIDGET + "=? and " + Widgets.ACCOUNT + "=?", new String[]{Integer.toString(AppWidgetManager.INVALID_APPWIDGET_ID), Long.toString(Sonet.INVALID_ACCOUNT_ID)}, null);
			}
			if (c.moveToFirst()) {
				status_bg_color = c.getInt(1);
				icon = c.getInt(2) == 1;
				profile_bg_color = c.getInt(7);
				friend_bg_color = c.getInt(8);
			}
			c.close();
			long id;
			long created = System.currentTimeMillis();
			int service = 0;
			boolean time24hr = false;
			long accountId = Sonet.INVALID_ACCOUNT_ID;
			String sid = "-1";
			String esid = "-1";
			String friend = getString(R.string.app_name);
			byte[] profile = getBlob(getResources(), R.drawable.icon);
			Cursor entity = getContentResolver().query(Entities.getContentUri(SonetService.this), new String[]{Entities._ID}, Entities.ACCOUNT + "=? and " + Entities.ESID + "=?", new String[]{Long.toString(accountId), mSonetCrypto.Encrypt(esid)}, null);
			if (entity.moveToFirst())
				id = entity.getLong(0);
			else {
				ContentValues entityValues = new ContentValues();
				entityValues.put(Entities.ESID, esid);
				entityValues.put(Entities.FRIEND, friend);
				entityValues.put(Entities.PROFILE, profile);
				entityValues.put(Entities.ACCOUNT, accountId);
				id = Long.parseLong(getContentResolver().insert(Entities.getContentUri(SonetService.this), entityValues).getLastPathSegment());
			}
			entity.close();
			ContentValues values = new ContentValues();
			values.put(Statuses.CREATED, created);
			values.put(Statuses.ENTITY, id);
			values.put(Statuses.MESSAGE, message);
			values.put(Statuses.SERVICE, service);
			values.put(Statuses.CREATEDTEXT, Sonet.getCreatedText(created, time24hr));
			values.put(Statuses.WIDGET, appWidgetId);
			values.put(Statuses.ACCOUNT, accountId);
			values.put(Statuses.SID, sid);
			values.put(Statuses.FRIEND_OVERRIDE, friend);
			values.put(Statuses.STATUS_BG, createBackground(status_bg_color));
			values.put(Statuses.FRIEND_BG, createBackground(friend_bg_color));
			values.put(Statuses.PROFILE_BG, createBackground(profile_bg_color));
			Bitmap emptyBmp = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
			ByteArrayOutputStream imageBgStream = new ByteArrayOutputStream();
			emptyBmp.compress(Bitmap.CompressFormat.PNG, 100, imageBgStream);
			byte[] emptyImg = imageBgStream.toByteArray();
			emptyBmp.recycle();
			emptyBmp = null;
			if (icon && (emptyImg != null))
				values.put(Statuses.ICON, emptyImg);
			long statusId = Long.parseLong(getContentResolver().insert(Statuses.getContentUri(SonetService.this), values).getLastPathSegment());
			// remote views can be reused, avoid images being repeated across multiple statuses
			if (emptyImg != null) {
				ContentValues imageValues = new ContentValues();
				imageValues.put(Status_images.STATUS_ID, statusId);
				imageValues.put(Status_images.IMAGE, emptyImg);
				imageValues.put(Status_images.IMAGE_BG, emptyImg);
				getContentResolver().insert(Status_images.getContentUri(SonetService.this), imageValues);
			}
		}

		private void addStatusItem(long created, String friend, String url, String message, int service, boolean time24hr, int appWidgetId, long accountId, String sid, String esid, ArrayList<String[]> links, HttpClient httpClient) {
			long id;
			byte[] profile = null;
			if (url != null)
				// get profile
				profile = SonetHttpClient.httpBlobResponse(httpClient, new HttpGet(url));
			if (profile == null)
				profile = getBlob(getResources(), R.drawable.ic_contact_picture);
			// facebook wall post handling
			String friend_override = null;
			if ((service == FACEBOOK) && friend.indexOf(">") > 0) {
				friend_override = friend;
				friend = friend.substring(0, friend.indexOf(">") - 1);
			}
			Cursor entity = getContentResolver().query(Entities.getContentUri(SonetService.this), new String[]{Entities._ID}, Entities.ACCOUNT + "=? and " + Entities.ESID + "=?", new String[]{Long.toString(accountId), mSonetCrypto.Encrypt(esid)}, null);
			if (entity.moveToFirst())
				id = entity.getLong(0);
			else {
				ContentValues entityValues = new ContentValues();
				entityValues.put(Entities.ESID, esid);
				entityValues.put(Entities.FRIEND, friend);
				entityValues.put(Entities.PROFILE, profile);
				entityValues.put(Entities.ACCOUNT, accountId);
				id = Long.parseLong(getContentResolver().insert(Entities.getContentUri(SonetService.this), entityValues).getLastPathSegment());
			}
			entity.close();
			// facebook sid comes in as esid_sid, the esid_ may need to be removed
			//		if (serviceId == FACEBOOK) {
			//			int split = sid.indexOf("_");
			//			if ((split > 0) && (split < sid.length())) {
			//				sid = sid.substring(sid.indexOf("_") + 1);
			//			}
			//		}
			// update the account statuses

			// parse any links
			Matcher m = Pattern.compile("\\bhttp(s)?://\\S+\\b", Pattern.CASE_INSENSITIVE).matcher(message);
			StringBuffer sb = new StringBuffer(message.length());
			while (m.find()) {
				String link = m.group();
				// check existing links before adding
				boolean exists = false;
				for (String[] l : links) {
					if (l[1].equals(link)) {
						exists = true;
						break;
					}
				}
				if (!exists) {
					links.add(new String[]{Slink, link});
					if ((service != TWITTER) && (service != IDENTICA))
						m.appendReplacement(sb, "(" + Slink + ": " + Uri.parse(link).getHost() + ")");
				}
			}
			m.appendTail(sb);
			message = sb.toString();
			ContentValues values = new ContentValues();
			values.put(Statuses.CREATED, created);
			values.put(Statuses.ENTITY, id);
			values.put(Statuses.MESSAGE, message);
			values.put(Statuses.SERVICE, service);
			values.put(Statuses.CREATEDTEXT, Sonet.getCreatedText(created, time24hr));
			values.put(Statuses.WIDGET, appWidgetId);
			values.put(Statuses.ACCOUNT, accountId);
			values.put(Statuses.SID, sid);
			values.put(Statuses.FRIEND_OVERRIDE, friend_override);
			long statusId = Long.parseLong(getContentResolver().insert(Statuses.getContentUri(SonetService.this), values).getLastPathSegment());
			String imageUrl = null;
			for (String[] s : links) {
				// get the first photo
				if (imageUrl == null) {
					Uri uri = Uri.parse(s[1]);
					if (((service == FACEBOOK) && (s[0].equals(Spicture))) || ((service == PINTEREST) && (s[0].equals(Simage))) || ((uri != null) && uri.getHost().equals(Simgur)))
						imageUrl = s[1];
				}
				ContentValues linkValues = new ContentValues();
				linkValues.put(Status_links.STATUS_ID, statusId);
				linkValues.put(Status_links.LINK_TYPE, s[0]);
				linkValues.put(Status_links.LINK_URI, s[1]);
				getContentResolver().insert(Status_links.getContentUri(SonetService.this), linkValues);
			}
			boolean insertEmptyImage = true;
			if (imageUrl != null) {
				byte[] image = null;
				if (url != null)
					image = SonetHttpClient.httpBlobResponse(httpClient, new HttpGet(imageUrl));
				if (image != null) {
					Bitmap imageBmp = BitmapFactory.decodeByteArray(image, 0, image.length, sBFOptions);
					if (imageBmp != null) {
						Bitmap scaledImageBmp = null;
						Bitmap croppedBmp = null;
						int width = imageBmp.getWidth();
						int height = imageBmp.getHeight();
						// default to landscape
						int scaledWidth;
						int scaledHeight;
						double targetHeightRatio;
						double targetWidthRatio;
						if (width > height) {
							//landscape
							scaledWidth = 192;
							scaledHeight = 144;
							targetHeightRatio = 0.75;
							targetWidthRatio = 4.0 / 3;
						} else {
							//portrait
							scaledWidth = 144;
							scaledHeight = 192;
							targetHeightRatio = 4.0 / 3;
							targetWidthRatio = 0.75;
						}
						int targetSize = (int) Math.round(width * targetHeightRatio);
						if (height > targetSize) {
							// center crop the height
							targetSize = getCropSize(height, targetSize);
							croppedBmp = Bitmap.createBitmap(imageBmp, 0, targetSize, width, height - targetSize);
						} else {
							targetSize = (int) Math.round(height * targetWidthRatio);
							if (width > targetSize) {
								// center crop the width
								targetSize = getCropSize(width, targetSize);
								croppedBmp = Bitmap.createBitmap(imageBmp, targetSize, 0, width - targetSize, height);
							}
						}
						if (croppedBmp != null) {
							scaledImageBmp = Bitmap.createScaledBitmap(croppedBmp, scaledWidth, scaledHeight, true);
							croppedBmp.recycle();
							croppedBmp = null;
						} else
							scaledImageBmp = Bitmap.createScaledBitmap(imageBmp, scaledWidth, scaledHeight, true);
						imageBmp.recycle();
						imageBmp = null;
						if (scaledImageBmp != null) {
							ByteArrayOutputStream imageStream = new ByteArrayOutputStream();
							scaledImageBmp.compress(Bitmap.CompressFormat.PNG, 100, imageStream);
							image = imageStream.toByteArray();
							scaledImageBmp.recycle();
							scaledImageBmp = null;
							if (image != null)
								insertEmptyImage = !insertStatusImageBg(SonetService.this, statusId, image, scaledHeight);
						}
					}
				}
			}
			// remote views can be reused, avoid images being repeated across multiple statuses
			if (insertEmptyImage)
				insertStatusImageBg(SonetService.this, statusId, null, 1);
		}

		private void addNotification(String sid, String esid, String friend, String message, long created, long accountId, String notification) {
			ContentValues values = new ContentValues();
			values.put(Notifications.SID, sid);
			values.put(Notifications.ESID, esid);
			values.put(Notifications.FRIEND, friend);
			values.put(Notifications.MESSAGE, message);
			values.put(Notifications.CREATED, created);
			values.put(Notifications.ACCOUNT, accountId);
			values.put(Notifications.NOTIFICATION, notification);
			values.put(Notifications.CLEARED, 0);
			values.put(Notifications.UPDATED, created);
			getContentResolver().insert(Notifications.getContentUri(SonetService.this), values);
			updateNotify(notification);
		}

		private void updateNotification(long notificationId, long created_time, String accountEsid, String esid, String name, boolean cleared) {
			// new comment
			ContentValues values = new ContentValues();
			values.put(Notifications.UPDATED, created_time);
			if (accountEsid.equals(esid)) {
				// user's own comment, clear the notification
				values.put(Notifications.CLEARED, 1);
			} else if (cleared) {
				values.put(Notifications.NOTIFICATION, String.format(getString(R.string.friendcommented), name));
				values.put(Notifications.CLEARED, 0);
				updateNotify(String.format(getString(R.string.friendcommented), name));
			} else {
				values.put(Notifications.NOTIFICATION, String.format(getString(R.string.friendcommented), name + " and others"));
				updateNotify(String.format(getString(R.string.friendcommented), name + " and others"));
			}
			getContentResolver().update(Notifications.getContentUri(SonetService.this), values, Notifications._ID + "=?", new String[]{Long.toString(notificationId)});
		}

		private void updateNotify(String notification) {
			if (mNotify == null)
				mNotify = notification;
			else
				mNotify = "multiple updates";
		}

		private boolean updateCreatedText(String widget, String account, boolean time24hr) {
			boolean statuses_updated = false;
			Cursor statuses = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses._ID, Statuses.CREATED}, Statuses.WIDGET + "=? and " + Statuses.ACCOUNT + "=?", new String[]{widget, account}, null);
			if (statuses.moveToFirst()) {
				while (!statuses.isAfterLast()) {
					ContentValues values = new ContentValues();
					values.put(Statuses.CREATEDTEXT, Sonet.getCreatedText(statuses.getLong(1), time24hr));
					getContentResolver().update(Statuses.getContentUri(SonetService.this), values, Statuses._ID + "=?", new String[]{Long.toString(statuses.getLong(0))});
					statuses.moveToNext();
				}
				statuses_updated = true;
			}
			statuses.close();
			return statuses_updated;
		}

		private long parseDate(String date, String format) {
			if (date != null) {
				// hack for the literal 'Z'
				if (date.substring(date.length() - 1).equals("Z"))
					date = date.substring(0, date.length() - 2) + "+0000";
				Date created = null;
				if (format != null) {
					if (mSimpleDateFormat == null) {
						mSimpleDateFormat = new SimpleDateFormat(format, Locale.ENGLISH);
						// all dates should be GMT/UTC
						mSimpleDateFormat.setTimeZone(sTimeZone);
					}
					try {
						created = mSimpleDateFormat.parse(date);
						return created.getTime();
					} catch (ParseException e) {
						Log.e(TAG, e.toString());
					}
				} else {
					// attempt to parse RSS date
					if (mSimpleDateFormat != null) {
						try {
							created = mSimpleDateFormat.parse(date);
							return created.getTime();
						} catch (ParseException e) {
							Log.e(TAG, e.toString());
						}
					}
					for (String rfc822 : sRFC822) {
						mSimpleDateFormat = new SimpleDateFormat(rfc822, Locale.ENGLISH);
						mSimpleDateFormat.setTimeZone(sTimeZone);
						try {
							if ((created = mSimpleDateFormat.parse(date)) != null)
								return created.getTime();
						} catch (ParseException e) {
							Log.e(TAG, e.toString());
						}
					}
				}
			}
			return System.currentTimeMillis();
		}

		private void updateTwitter(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			Cursor currentNotifications;
			String sid;
			String friend;
			SonetOAuth sonetOAuth = new SonetOAuth(BuildConfig.TWITTER_KEY, BuildConfig.TWITTER_SECRET, token, secret);
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(TWITTER_URL_FEED, TWITTER_BASE_URL, status_count))))) != null) {
				// if not a full_refresh, only update the status_bg and icons
				try {
					statusesArray = new JSONArray(response);
					// if there are updates, clear the cache
					int e2 = statusesArray.length();
					if (e2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int e = 0; (e < e2) && (e < status_count); e++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(e);
							friendObj = statusObj.getJSONObject(Suser);
							addStatusItem(parseDate(statusObj.getString(Screated_at), TWITTER_DATE_FORMAT),
									friendObj.getString(Sname),
									display_profile ? friendObj.getString(Sprofile_image_url) : null,
											statusObj.getString(Stext),
											service,
											time24hr,
											appWidgetId,
											account,
											statusObj.getString(Sid),
											friendObj.getString(Sid),
											links,
											httpClient);
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
				}
			}
			// notifications
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications.SID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					// store sids, to avoid duplicates when requesting the latest feed
					sid = mSonetCrypto.Decrypt(currentNotifications.getString(0));
					if (!notificationSids.contains(sid))
						notificationSids.add(sid);
				}
				currentNotifications.close();
				// limit to oldest status
				String last_sid = null;
				Cursor last_status = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses.SID}, Statuses.ACCOUNT + "=?", new String[]{Long.toString(account)}, Statuses.CREATED + " ASC LIMIT 1");
				if (last_status.moveToFirst())
					last_sid = mSonetCrypto.Decrypt(last_status.getString(0));
				last_status.close();
				// get all mentions since the oldest status for this account
				if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(TWITTER_MENTIONS, TWITTER_BASE_URL, last_sid != null ? String.format(TWITTER_SINCE_ID, last_sid) : ""))))) != null) {
					try {
						statusesArray = new JSONArray(response);
						for (int i = 0, i2 = statusesArray.length(); i < i2; i++) {
							statusObj = statusesArray.getJSONObject(i);
							friendObj = statusObj.getJSONObject(Suser);
							if (!friendObj.getString(Sid).equals(accountEsid) && !notificationSids.contains(statusObj.getString(Sid))) {
								friend = friendObj.getString(Sname);
								addNotification(statusObj.getString(Sid), friendObj.getString(Sid), friend, statusObj.getString(Stext), parseDate(statusObj.getString(Screated_at), TWITTER_DATE_FORMAT), account, friend + " mentioned you on Twitter");
							}
						}
					} catch (JSONException e) {
						Log.e(TAG, service + ":" + e.toString());
					}
				}
			}
		}

		private void updateFacebook(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			JSONArray commentsArray;
			JSONObject commentObj;
			Cursor currentNotifications;
			String sid;
			String esid;
			long notificationId;
			long updated;
			boolean cleared;
			String friend;
			// notifications first to populate notificationsSids
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications._ID, Notifications.SID, Notifications.UPDATED, Notifications.CLEARED, Notifications.ESID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					while (!currentNotifications.isAfterLast()) {
						notificationId = currentNotifications.getLong(0);
						sid = mSonetCrypto.Decrypt(currentNotifications.getString(1));
						updated = currentNotifications.getLong(2);
						cleared = currentNotifications.getInt(3) == 1;
						// store sids, to avoid duplicates when requesting the latest feed
						if (!notificationSids.contains(sid))
							notificationSids.add(sid);
						// get comments for current notifications
						if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(FACEBOOK_COMMENTS, FACEBOOK_BASE_URL, sid, Saccess_token, token)))) != null) {
							// check for a newer post, if it's the user's own, then set CLEARED=0
							try {
								commentsArray = new JSONObject(response).getJSONArray(Sdata);
								final int i2 = commentsArray.length();
								if (i2 > 0) {
									for (int i = 0; i < i2; i++) {
										commentObj = commentsArray.getJSONObject(i);
										final long created_time = commentObj.getLong(Screated_time) * 1000;
										if (created_time > updated) {
											final JSONObject from = commentObj.getJSONObject(Sfrom);
											updateNotification(notificationId, created_time, accountEsid, from.getString(Sid), from.getString(Sname), cleared);
										}
									}
								}
							} catch (JSONException e) {
								Log.e(TAG, service + ":" + e.toString() + ":" + response);
							}
						}
						currentNotifications.moveToNext();
					}
				}
				currentNotifications.close();
			}
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(FACEBOOK_HOME, FACEBOOK_BASE_URL, Saccess_token, token)))) != null) {
				try {
					statusesArray = new JSONObject(response).getJSONArray(Sdata);
					// if there are updates, clear the cache
					int d2 = statusesArray.length();
					if (d2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int d = 0; d < d2; d++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(d);
							// only parse status types, not photo, video or link
							if (statusObj.has(Stype) && statusObj.has(Sfrom) && statusObj.has(Sid)) {
								friendObj = statusObj.getJSONObject("from");
								if (friendObj.has(Sname) && friendObj.has(Sid)) {
									friend = friendObj.getString(Sname);
									esid = friendObj.getString(Sid);
									sid = statusObj.getString(Sid);
									StringBuilder message = new StringBuilder();
									if (statusObj.has(Smessage))
										message.append(statusObj.getString(Smessage));
									else if (statusObj.has(Sstory))
										message.append(statusObj.getString(Sstory));
									if (statusObj.has(Spicture))
										links.add(new String[]{Spicture, statusObj.getString(Spicture)});
									if (statusObj.has(Slink)) {
										links.add(new String[]{statusObj.getString(Stype), statusObj.getString(Slink)});
										if (!statusObj.has(Spicture) || !statusObj.getString(Stype).equals(Sphoto)) {
											message.append("(");
											message.append(statusObj.getString(Stype));
											message.append(": ");
											message.append(Uri.parse(statusObj.getString(Slink)).getHost());
											message.append(")");
										}
									}
									if (statusObj.has(Ssource)) {
										links.add(new String[]{statusObj.getString(Stype), statusObj.getString(Ssource)});
										if (!statusObj.has(Spicture) || !statusObj.getString(Stype).equals(Sphoto)) {
											message.append("(");
											message.append(statusObj.getString(Stype));
											message.append(": ");
											message.append(Uri.parse(statusObj.getString(Ssource)).getHost());
											message.append(")");
										}
									}
									long date = statusObj.getLong(Screated_time) * 1000;
									String notification = null;
									if (statusObj.has(Sto)) {
										// handle wall messages from one friend to another
										JSONObject t = statusObj.getJSONObject(Sto);
										if (t.has(Sdata)) {
											JSONObject n = t.getJSONArray(Sdata).getJSONObject(0);
											if (n.has(Sname)) {
												friend += " > " + n.getString(Sname);
												if (!notificationSids.contains(sid) && n.has(Sid) && (n.getString(Sid).equals(accountEsid)))
													notification = String.format(getString(R.string.friendcommented), friend);
											}
										}												
									}
									int commentCount = 0;
									if (statusObj.has(Scomments)) {
										JSONObject jo = statusObj.getJSONObject(Scomments);
										if (jo.has(Sdata)) {
											commentsArray = jo.getJSONArray(Sdata);
											commentCount = commentsArray.length();
											if (!notificationSids.contains(sid) && (commentCount > 0)) {
												// default hasCommented to whether or not these comments are for the own user's status
												boolean hasCommented = notification != null || esid.equals(accountEsid);
												for (int c2 = 0; c2 < commentCount; c2++) {
													commentObj = commentsArray.getJSONObject(c2);
													// if new notification, or updated
													if (commentObj.has(Sfrom)) {
														JSONObject c4 = commentObj.getJSONObject(Sfrom);
														if (c4.getString(Sid).equals(accountEsid)) {
															if (!hasCommented) {
																// the user has commented on this thread, notify any updates
																hasCommented = true;
															}
															// clear any notifications, as the user is already aware
															if (notification != null)
																notification = null;
														} else if (hasCommented) {
															// don't notify about user's own comments
															// send the parent comment sid
															notification = String.format(getString(R.string.friendcommented), c4.getString(Sname));
														}
													}
												}
											}
										}
									}
									if ((notifications != 0) && (notification != null)) {
										// new notification
										addNotification(sid, esid, friend, message.toString(), statusObj.getLong(Screated_time) * 1000, account, notification);
									}
									if (d < status_count) {
										addStatusItem(date,
												friend,
												display_profile ? String.format(FACEBOOK_PICTURE, esid) : null,
														String.format(getString(R.string.messageWithCommentCount), message.toString(), commentCount),
														service,
														time24hr,
														appWidgetId,
														account,
														sid,
														esid,
														links,
														httpClient);
									}
								}
							}
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString() + ":" + response);
				}
			}
		}

		private void updateMySpace(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			JSONArray commentsArray;
			JSONObject commentObj;
			Cursor currentNotifications;
			String sid;
			String esid;
			long notificationId;
			long updated;
			boolean cleared;
			String friend;
			SonetOAuth sonetOAuth = new SonetOAuth(BuildConfig.MYSPACE_KEY, BuildConfig.MYSPACE_SECRET, token, secret);
			// notifications
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications._ID, Notifications.SID, Notifications.UPDATED, Notifications.CLEARED, Notifications.ESID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					while (!currentNotifications.isAfterLast()) {
						notificationId = currentNotifications.getLong(0);
						sid = mSonetCrypto.Decrypt(currentNotifications.getString(1));
						updated = currentNotifications.getLong(2);
						cleared = currentNotifications.getInt(3) == 1;
						esid = mSonetCrypto.Decrypt(currentNotifications.getString(4));
						// store sids, to avoid duplicates when requesting the latest feed
						if (!notificationSids.contains(sid))
							notificationSids.add(sid);
						// get comments for current notifications
						if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(MYSPACE_URL_STATUSMOODCOMMENTS, MYSPACE_BASE_URL, esid, sid))))) != null) {
							// check for a newer post, if it's the user's own, then set CLEARED=0
							try {
								commentsArray = new JSONObject(response).getJSONArray(Sentry);
								final int i2 = commentsArray.length();
								if (i2 > 0) {
									for (int i = 0; i < i2; i++) {
										commentObj = commentsArray.getJSONObject(i);
										long created_time = parseDate(commentObj.getString(SpostedDate), MYSPACE_DATE_FORMAT);
										if (created_time > updated) {
											friendObj = commentObj.getJSONObject(Sauthor);
											updateNotification(notificationId, created_time, accountEsid, friendObj.getString(Sid), friendObj.getString(SdisplayName), cleared);
										}
									}
								}
							} catch (JSONException e) {
								Log.e(TAG, service + ":" + e.toString());
							}
						}
						currentNotifications.moveToNext();
					}
				}
				currentNotifications.close();
			}
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(MYSPACE_HISTORY, MYSPACE_BASE_URL))))) != null) {
				try {
					statusesArray = new JSONObject(response).getJSONArray(Sentry);
					// if there are updates, clear the cache
					int e2 = statusesArray.length();
					if (e2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int e = 0; e < e2; e++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(e);
							friendObj = statusObj.getJSONObject(Sauthor);
							long date = parseDate(statusObj.getString(SmoodStatusLastUpdated), MYSPACE_DATE_FORMAT);
							esid = statusObj.getString(SuserId);
							int commentCount = 0;
							sid = statusObj.getString(SstatusId);
							friend = friendObj.getString(SdisplayName);
							String statusValue = statusObj.getString(Sstatus);
							String notification = null;
							if (statusObj.has(SrecentComments)) {
								commentsArray = statusObj.getJSONArray(SrecentComments);
								commentCount = commentsArray.length();
								// notifications
								if ((sid != null) && !notificationSids.contains(sid) && (commentCount > 0)) {
									// default hasCommented to whether or not these comments are for the own user's status
									boolean hasCommented = notification != null || esid.equals(accountEsid);
									for (int c2 = 0; c2 < commentCount; c2++) {
										commentObj = commentsArray.getJSONObject(c2);
										if (commentObj.has(Sauthor)) {
											JSONObject c4 = commentObj.getJSONObject(Sauthor);
											if (c4.getString(Sid).equals(accountEsid)) {
												if (!hasCommented) {
													// the user has commented on this thread, notify any updates
													hasCommented = true;
												}
												// clear any notifications, as the user is already aware
												if (notification != null)
													notification = null;
											} else if (hasCommented) {
												// don't notify about user's own comments
												// send the parent comment sid
												notification = String.format(getString(R.string.friendcommented), c4.getString(SdisplayName));
											}
										}
									}
								}
							}
							if ((notifications != 0) && (notification != null)) {
								// new notification
								addNotification(sid, esid, friend, statusValue, date, account, notification);
							}
							if (e < status_count) {
								addStatusItem(date,
										friend,
										display_profile ? friendObj.getString(SthumbnailUrl) : null,
												String.format(getString(R.string.messageWithCommentCount), statusValue, commentCount),
												service,
												time24hr,
												appWidgetId,
												account,
												sid,
												esid,
												links,
												httpClient);
							}
						}
					} else {
						// warn about myspace permissions
						addStatusItem(0,
								getString(R.string.myspace_permissions_title),
								null,
								getString(R.string.myspace_permissions_message),
								service,
								time24hr,
								appWidgetId,
								account,
								"",
								"",
								new ArrayList<String[]>(),
								httpClient);
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
				}
			} else {
				// warn about myspace permissions
				addStatusItem(0,
						getString(R.string.myspace_permissions_title),
						null,
						getString(R.string.myspace_permissions_message),
						service,
						time24hr,
						appWidgetId,
						account,
						"",
						"",
						new ArrayList<String[]>(),
						httpClient);
			}
		}

		private void updateFoursquare(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			JSONArray commentsArray;
			JSONObject commentObj;
			Cursor currentNotifications;
			String sid;
			String esid;
			long notificationId;
			long updated;
			boolean cleared;
			String friend;
			// notifications
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications._ID, Notifications.SID, Notifications.UPDATED, Notifications.CLEARED, Notifications.ESID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					while (!currentNotifications.isAfterLast()) {
						notificationId = currentNotifications.getLong(0);
						sid = mSonetCrypto.Decrypt(currentNotifications.getString(1));
						updated = currentNotifications.getLong(2);
						cleared = currentNotifications.getInt(3) == 1;
						// store sids, to avoid duplicates when requesting the latest feed
						if (!notificationSids.contains(sid))
							notificationSids.add(sid);
						// get comments for current notifications
						if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(FOURSQUARE_GET_CHECKIN, FOURSQUARE_BASE_URL, sid, token)))) != null) {
							// check for a newer post, if it's the user's own, then set CLEARED=0
							try {
								commentsArray = new JSONObject(response).getJSONObject(Sresponse).getJSONObject(Scheckin).getJSONObject(Scomments).getJSONArray(Sitems);
								int i2 = commentsArray.length();
								if (i2 > 0) {
									for (int i = 0; i < i2; i++) {
										commentObj = commentsArray.getJSONObject(i);
										long created_time = commentObj.getLong(ScreatedAt) * 1000;
										if (created_time > updated) {
											friendObj = commentObj.getJSONObject(Suser);
											updateNotification(notificationId, created_time, accountEsid, friendObj.getString(Sid), friendObj.getString(SfirstName) + " " + friendObj.getString(SlastName), cleared);
										}
									}
								}
							} catch (JSONException e) {
								Log.e(TAG, service + ":" + e.toString());
							}
						}
						currentNotifications.moveToNext();
					}
				}
				currentNotifications.close();
			}
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(FOURSQUARE_CHECKINS, FOURSQUARE_BASE_URL, token)))) != null) {
				try {
					statusesArray = new JSONObject(response).getJSONObject(Sresponse).getJSONArray(Srecent);
					// if there are updates, clear the cache
					int e2 = statusesArray.length();
					if (e2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int e = 0; e < e2; e++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(e);
							friendObj = statusObj.getJSONObject(Suser);
							String shout = "";
							if (statusObj.has(Sshout))
								shout = statusObj.getString(Sshout) + "\n";
							if (statusObj.has(Svenue)) {
								JSONObject venue = statusObj.getJSONObject(Svenue);
								if (venue.has(Sname))
									shout += "@" + venue.getString(Sname);
							}
							long date = statusObj.getLong(ScreatedAt) * 1000;
							// notifications
							esid = friendObj.getString(Sid);
							int commentCount = 0;
							sid = statusObj.getString(Sid);
							friend = friendObj.getString(SfirstName) + " " + friendObj.getString(SlastName);
							String notification = null;
							if (statusObj.has(Scomments)) {
								commentsArray = statusObj.getJSONObject(Scomments).getJSONArray(Sitems);
								commentCount = commentsArray.length();
								if (!notificationSids.contains(sid) && (commentCount > 0)) {
									// default hasCommented to whether or not these comments are for the own user's status
									boolean hasCommented = notification != null || esid.equals(accountEsid);
									for (int c2 = 0; c2 < commentCount; c2++) {
										commentObj = commentsArray.getJSONObject(c2);
										if (commentObj.has(Suser)) {
											JSONObject c4 = commentObj.getJSONObject(Suser);
											if (c4.getString(Sid).equals(accountEsid)) {
												if (!hasCommented) {
													// the user has commented on this thread, notify any updates
													hasCommented = true;
												}
												// clear any notifications, as the user is already aware
												if (notification != null)
													notification = null;
											} else if (hasCommented) {
												// don't notify about user's own comments
												// send the parent comment sid
												notification = String.format(getString(R.string.friendcommented), c4.getString(SfirstName) + " " + c4.getString(SlastName));
											}
										}
									}
								}
							}
							if ((notifications != 0) && (notification != null)) {
								// new notification
								addNotification(sid, esid, friend, shout, date, account, notification);
							}
							if (e < status_count) {
								addStatusItem(date,
										friend,
										display_profile ? friendObj.getString(Sphoto) : null,
												String.format(getString(R.string.messageWithCommentCount), shout, commentCount),
												service,
												time24hr,
												appWidgetId,
												account,
												sid,
												esid,
												links,
												httpClient);
							}
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
					Log.e(TAG, response);
				}
			}
		}

		private void updateLinkedIn(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			HttpGet httpGet;
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			JSONArray commentsArray;
			JSONObject commentObj;
			Cursor currentNotifications;
			String sid;
			String esid;
			long notificationId;
			long updated;
			boolean cleared;
			String friend;
			SonetOAuth sonetOAuth = new SonetOAuth(BuildConfig.LINKEDIN_KEY, BuildConfig.LINKEDIN_SECRET, token, secret);
			// notifications
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications._ID, Notifications.SID, Notifications.UPDATED, Notifications.CLEARED, Notifications.ESID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					while (!currentNotifications.isAfterLast()) {
						notificationId = currentNotifications.getLong(0);
						sid = mSonetCrypto.Decrypt(currentNotifications.getString(1));
						updated = currentNotifications.getLong(2);
						cleared = currentNotifications.getInt(3) == 1;
						// store sids, to avoid duplicates when requesting the latest feed
						if (!notificationSids.contains(sid))
							notificationSids.add(sid);
						// get comments for current notifications
						httpGet = new HttpGet(String.format(LINKEDIN_UPDATE_COMMENTS, LINKEDIN_BASE_URL, sid));
						for (String[] header : LINKEDIN_HEADERS) httpGet.setHeader(header[0], header[1]);
						if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(httpGet))) != null) {
							// check for a newer post, if it's the user's own, then set CLEARED=0
							try {
								JSONObject jsonResponse = new JSONObject(response);
								if (jsonResponse.has(S_total) && (jsonResponse.getInt(S_total) != 0)) {
									commentsArray = jsonResponse.getJSONArray(Svalues);
									int i2 = commentsArray.length();
									if (i2 > 0) {
										for (int i = 0; i < i2; i++) {
											commentObj = commentsArray.getJSONObject(i);
											long created_time = commentObj.getLong(Stimestamp);
											if (created_time > updated) {
												friendObj = commentObj.getJSONObject(Sperson);
												updateNotification(notificationId, created_time, accountEsid, friendObj.getString(Sid), friendObj.getString(SfirstName) + " " + friendObj.getString(SlastName), cleared);
											}
										}
									}
								}
							} catch (JSONException e) {
								Log.e(TAG, service + ":" + e.toString());
							}
						}
						currentNotifications.moveToNext();
					}
				}
				currentNotifications.close();
			}
			httpGet = new HttpGet(String.format(LINKEDIN_UPDATES, LINKEDIN_BASE_URL));
			for (String[] header : LINKEDIN_HEADERS)
				httpGet.setHeader(header[0], header[1]);
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(httpGet))) != null) {
				try {
					statusesArray = new JSONObject(response).getJSONArray(Svalues);
					// if there are updates, clear the cache
					int e2 = statusesArray.length();
					if (e2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int e = 0; e < e2; e++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(e);
							String updateType = statusObj.getString(SupdateType);
							JSONObject updateContent = statusObj.getJSONObject(SupdateContent);
							String update = null;
							if (((update = LinkedIn_UpdateTypes.getMessage(updateType)) != null) && updateContent.has(Sperson)) {
								friendObj = updateContent.getJSONObject(Sperson);
								if (LinkedIn_UpdateTypes.APPS.name().equals(updateType)) {
									if (friendObj.has(SpersonActivities)) {
										JSONObject personActivities = friendObj.getJSONObject(SpersonActivities);
										if (personActivities.has(Svalues)) {
											JSONArray updates = personActivities.getJSONArray(Svalues);
											for (int u = 0, u2 = updates.length(); u < u2; u++) {
												update += updates.getJSONObject(u).getString(Sbody);
												if (u < (updates.length() - 1))
													update += ", ";
											}
										}
									}
								} else if (LinkedIn_UpdateTypes.CONN.name().equals(updateType)) {
									if (friendObj.has(Sconnections)) {
										JSONObject connections = friendObj.getJSONObject(Sconnections);
										if (connections.has(Svalues)) {
											JSONArray updates = connections.getJSONArray(Svalues);
											for (int u = 0, u2 = updates.length(); u < u2; u++) {
												update += updates.getJSONObject(u).getString(SfirstName) + " " + updates.getJSONObject(u).getString(SlastName);
												if (u < (updates.length() - 1))
													update += ", ";
											}
										}
									}
								} else if (LinkedIn_UpdateTypes.JOBP.name().equals(updateType)) {
									if (updateContent.has(Sjob) && updateContent.getJSONObject(Sjob).has(Sposition) && updateContent.getJSONObject(Sjob).getJSONObject(Sposition).has(Stitle))
										update += updateContent.getJSONObject(Sjob).getJSONObject(Sposition).getString(Stitle);
								} else if (LinkedIn_UpdateTypes.JGRP.name().equals(updateType)) {
									if (friendObj.has(SmemberGroups)) {
										JSONObject memberGroups = friendObj.getJSONObject(SmemberGroups);
										if (memberGroups.has(Svalues)) {
											JSONArray updates = memberGroups.getJSONArray(Svalues);
											for (int u = 0, u2 = updates.length(); u < u2; u++) {
												update += updates.getJSONObject(u).getString(Sname);
												if (u < (updates.length() - 1))
													update += ", ";
											}
										}
									}
								} else if (LinkedIn_UpdateTypes.PREC.name().equals(updateType)) {
									if (friendObj.has(SrecommendationsGiven)) {
										JSONObject recommendationsGiven = friendObj.getJSONObject(SrecommendationsGiven);
										if (recommendationsGiven.has(Svalues)) {
											JSONArray updates = recommendationsGiven.getJSONArray(Svalues);
											for (int u = 0, u2 = updates.length(); u < u2; u++) {
												JSONObject recommendation = updates.getJSONObject(u);
												JSONObject recommendee = recommendation.getJSONObject(Srecommendee);
												if (recommendee.has(SfirstName)) update += recommendee.getString(SfirstName);
												if (recommendee.has(SlastName)) update += recommendee.getString(SlastName);
												if (recommendation.has(SrecommendationSnippet)) update += ":" + recommendation.getString(SrecommendationSnippet);
												if (u < (updates.length() - 1))
													update += ", ";
											}
										}
									}
								} else if (LinkedIn_UpdateTypes.SHAR.name().equals(updateType) && friendObj.has(ScurrentShare)) {
									JSONObject currentShare = friendObj.getJSONObject(ScurrentShare);
									if (currentShare.has(Scomment))
										update = currentShare.getString(Scomment);
								}
								long date = statusObj.getLong(Stimestamp);
								sid = statusObj.has(SupdateKey) ? statusObj.getString(SupdateKey) : null;
								esid = friendObj.getString(Sid);
								friend = friendObj.getString(SfirstName) + " " + friendObj.getString(SlastName);
								int commentCount = 0;
								String notification = null;
								if (statusObj.has(SupdateComments)) {
									JSONObject updateComments = statusObj.getJSONObject(SupdateComments);
									if (updateComments.has(Svalues)) {
										commentsArray = updateComments.getJSONArray(Svalues);
										commentCount = commentsArray.length();
										if (!notificationSids.contains(sid) && (commentCount > 0)) {
											// default hasCommented to whether or not these comments are for the own user's status
											boolean hasCommented = notification != null || esid.equals(accountEsid);
											for (int c2 = 0; c2 < commentCount; c2++) {
												commentObj = commentsArray.getJSONObject(c2);
												if (commentObj.has(Sperson)) {
													JSONObject c4 = commentObj.getJSONObject(Sperson);
													if (c4.getString(Sid).equals(accountEsid)) {
														if (!hasCommented) {
															// the user has commented on this thread, notify any updates
															hasCommented = true;
														}
														// clear any notifications, as the user is already aware
														if (notification != null)
															notification = null;
													} else if (hasCommented) {
														// don't notify about user's own comments
														// send the parent comment sid
														notification = String.format(getString(R.string.friendcommented), c4.getString(SfirstName) + " " + c4.getString(SlastName));
													}
												}
											}
										}
									}
								}
								if ((notifications != 0) && (notification != null)) {
									// new notification
									addNotification(sid, esid, friend, update, date, account, notification);
								}
								if (e < status_count) {
									addStatusItem(date,
											friend,
											display_profile && friendObj.has(SpictureUrl) ? friendObj.getString(SpictureUrl) : null,
													(statusObj.has(SisCommentable) && statusObj.getBoolean(SisCommentable) ? String.format(getString(R.string.messageWithCommentCount), update, commentCount) : update),
													service,
													time24hr,
													appWidgetId,
													account,
													sid,
													esid,
													links,
													httpClient);
								}
							}
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
				}
			}
		}

		private void updateRSS(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			ArrayList<String[]> links = new ArrayList<String[]>();
			String response = SonetHttpClient.httpResponse(httpClient, new HttpGet(accountEsid));
			if (response != null) {
				DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
				try {
					DocumentBuilder db = dbf.newDocumentBuilder();
					InputSource is = new InputSource();
					is.setCharacterStream(new StringReader(response));
					Document doc = db.parse(is);
					NodeList nodes = doc.getElementsByTagName(Sitem);
					int i2 = nodes.getLength();
					if (i2 > 0) {
						// check for an image
						String image_url = null;
						NodeList images = doc.getElementsByTagName(Simage);
						int i3 = images.getLength();
						if (i3 > 0) {
							NodeList imageChildren = images.item(0).getChildNodes();
							for (int i = 0; (i < i3) && (image_url == null); i++) {
								Node n = imageChildren.item(i);
								if (n.getNodeName().toLowerCase().equals(Surl)) {
									if (n.hasChildNodes())
										image_url = n.getChildNodes().item(0).getNodeValue();
								}
							}
						}
						removeOldStatuses(widget, Long.toString(account));
						int item_count = 0;
						for (int i = 0; (i < i2) && (item_count < status_count); i++) {
							links.clear();
							NodeList children = nodes.item(i).getChildNodes();
							String date = null;
							String title = null;
							String description = null;
							String link = null;
							int values_count = 0;
							for (int child = 0, c2 = children.getLength(); (child < c2) && (values_count < 4); child++) {
								Node n = children.item(child);
								final String nodeName = n.getNodeName().toLowerCase();
								if (nodeName.equals(Spubdate)) {
									values_count++;
									if (n.hasChildNodes())
										date = n.getChildNodes().item(0).getNodeValue();
								} else if (nodeName.equals(Stitle)) {
									values_count++;
									if (n.hasChildNodes())
										title = n.getChildNodes().item(0).getNodeValue();
								} else if (nodeName.equals(Sdescription)) {
									values_count++;
									if (n.hasChildNodes()) {
										StringBuilder sb = new StringBuilder();
										NodeList descNodes = n.getChildNodes();
										for (int dn = 0, dn2 = descNodes.getLength(); dn < dn2; dn++) {
											Node descNode = descNodes.item(dn);
											if (descNode.getNodeType() == Node.TEXT_NODE)
												sb.append(descNode.getNodeValue());
										}
										// strip out the html tags
										description = sb.toString().replaceAll("\\<(.|\n)*?>", "");
									}
								} else if (nodeName.equals(Slink)) {
									values_count++;
									if (n.hasChildNodes())
										link = n.getChildNodes().item(0).getNodeValue();
								}
							}
							if (Sonet.HasValues(new String[]{title, description, link, date})) {
								item_count++;
								addStatusItem(parseDate(date, null), title, display_profile ? image_url : null, description, service, time24hr, appWidgetId, account, null, link, links, httpClient);
							}
						}
					}
				} catch (ParserConfigurationException e) {
					Log.e(TAG, "RSS:" + e.toString());
				} catch (SAXException e) {
					Log.e(TAG, "RSS:" + e.toString());
				} catch (IOException e) {
					Log.e(TAG, "RSS:" + e.toString());
				}
			}
		}

		private void updateIdentiCa(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			Cursor currentNotifications;
			String sid;
			String friend;
			SonetOAuth sonetOAuth = new SonetOAuth(BuildConfig.IDENTICA_KEY, BuildConfig.IDENTICA_SECRET, token, secret);
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(IDENTICA_URL_FEED, IDENTICA_BASE_URL, status_count))))) != null) {
				// if not a full_refresh, only update the status_bg and icons
				try {
					statusesArray = new JSONArray(response);
					// if there are updates, clear the cache
					int e2 = statusesArray.length();
					if (e2 > 0) {
						removeOldStatuses(widget, Long.toString(account));
						for (int e = 0; e < e2; e++) {
							links.clear();
							statusObj = statusesArray.getJSONObject(e);
							friendObj = statusObj.getJSONObject(Suser);
							long date = parseDate(statusObj.getString(Screated_at), TWITTER_DATE_FORMAT);
							addStatusItem(date,
									friendObj.getString(Sname),
									display_profile ? friendObj.getString(Sprofile_image_url) : null,
											statusObj.getString(Stext),
											service,
											time24hr,
											appWidgetId,
											account,
											statusObj.getString(Sid),
											friendObj.getString(Sid),
											links,
											httpClient);
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
				}
			}
			// notifications
			if (notifications != 0) {
				currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications.SID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
				// loop over notifications
				if (currentNotifications.moveToFirst()) {
					// store sids, to avoid duplicates when requesting the latest feed
					sid = mSonetCrypto.Decrypt(currentNotifications.getString(0));
					if (!notificationSids.contains(sid))
						notificationSids.add(sid);
				}
				currentNotifications.close();
				// limit to oldest status
				String last_sid = null;
				Cursor last_status = getContentResolver().query(Statuses.getContentUri(SonetService.this), new String[]{Statuses.SID}, Statuses.ACCOUNT + "=?", new String[]{Long.toString(account)}, Statuses.CREATED + " ASC LIMIT 1");
				if (last_status.moveToFirst())
					last_sid = mSonetCrypto.Decrypt(last_status.getString(0));
				last_status.close();
				// get all mentions since the oldest status for this account
				if ((response = SonetHttpClient.httpResponse(httpClient, sonetOAuth.getSignedRequest(new HttpGet(String.format(IDENTICA_MENTIONS, IDENTICA_BASE_URL, last_sid != null ? String.format(IDENTICA_SINCE_ID, last_sid) : ""))))) != null) {
					try {
						statusesArray = new JSONArray(response);
						for (int i = 0, i2 = statusesArray.length(); i < i2; i++) {
							statusObj = statusesArray.getJSONObject(i);
							friendObj = statusObj.getJSONObject(Suser);
							if (!friendObj.getString(Sid).equals(accountEsid) && !notificationSids.contains(statusObj.getString(Sid))) {
								friend = friendObj.getString(Sname);
								addNotification(statusObj.getString(Sid), friendObj.getString(Sid), friend, statusObj.getString(Stext), parseDate(statusObj.getString(Screated_at), TWITTER_DATE_FORMAT), account, friend + " mentioned you on Identi.ca");
							}
						}
					} catch (JSONException e) {
						Log.e(TAG, service + ":" + e.toString());
					}
				}
			}
		}

		private void updateGooglePlus(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			final ArrayList<String> notificationSids = new ArrayList<String>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			Cursor currentNotifications;
			String sid;
			String esid;
			long notificationId;
			long updated;
			boolean cleared;
			String friend;
			// get new access token, need different request here
			HttpPost httpPost = new HttpPost(GOOGLE_ACCESS);
			List<NameValuePair> httpParams = new ArrayList<NameValuePair>();
			httpParams.add(new BasicNameValuePair("client_id", BuildConfig.GOOGLECLIENT_ID));
			httpParams.add(new BasicNameValuePair("client_secret", BuildConfig.GOOGLECLIENT_SECRET));
			httpParams.add(new BasicNameValuePair("refresh_token", token));
			httpParams.add(new BasicNameValuePair("grant_type", "refresh_token"));
			try {
				httpPost.setEntity(new UrlEncodedFormEntity(httpParams));
				if ((response = SonetHttpClient.httpResponse(httpClient, httpPost)) != null) {
					JSONObject j = new JSONObject(response);
					if (j.has("access_token")) {
						String access_token = j.getString("access_token");
						// notifications
						if (notifications != 0) {
							currentNotifications = getContentResolver().query(Notifications.getContentUri(SonetService.this), new String[]{Notifications._ID, Notifications.SID, Notifications.UPDATED, Notifications.CLEARED, Notifications.ESID}, Notifications.ACCOUNT + "=?", new String[]{Long.toString(account)}, null);
							// loop over notifications
							if (currentNotifications.moveToFirst()) {
								while (!currentNotifications.isAfterLast()) {
									notificationId = currentNotifications.getLong(0);
									sid = mSonetCrypto.Decrypt(currentNotifications.getString(1));
									updated = currentNotifications.getLong(2);
									cleared = currentNotifications.getInt(3) == 1;
									// store sids, to avoid duplicates when requesting the latest feed
									if (!notificationSids.contains(sid)) {
										notificationSids.add(sid);
									}
									// get comments for current notifications
									if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(GOOGLEPLUS_ACTIVITY, GOOGLEPLUS_BASE_URL, sid, access_token)))) != null) {
										// check for a newer post, if it's the user's own, then set CLEARED=0
										try {
											JSONObject item = new JSONObject(response);
											if (item.has(Sobject)) {
												JSONObject object = item.getJSONObject(Sobject);
												if (object.has(Sreplies)) {
													int commentCount = 0;
													JSONObject replies = object.getJSONObject(Sreplies);
													if (replies.has(StotalItems))
														commentCount = replies.getInt(StotalItems);
												}
											}
										} catch (JSONException e) {
											Log.e(TAG, service + ":" + e.toString());
										}
									}
									currentNotifications.moveToNext();
								}
							}
							currentNotifications.close();
						}
						// get new feed
						if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(GOOGLEPLUS_ACTIVITIES, GOOGLEPLUS_BASE_URL, "me", "public", status_count, access_token)))) != null) {
							JSONObject r = new JSONObject(response);
							if (r.has(Sitems)) {
								statusesArray = r.getJSONArray(Sitems);
								removeOldStatuses(widget, Long.toString(account));
								for (int i1 = 0, i2 = statusesArray.length(); i1 < i2; i1++) {
									statusObj = statusesArray.getJSONObject(i1);
									if (statusObj.has(Sactor) && statusObj.has(Sobject)) {
										friendObj = statusObj.getJSONObject(Sactor);
										JSONObject object = statusObj.getJSONObject(Sobject);
										if (statusObj.has(Sid) && friendObj.has(Sid) && friendObj.has(SdisplayName) && statusObj.has(Spublished) && object.has(Sreplies) && object.has(SoriginalContent)) {
											sid = statusObj.getString(Sid);
											esid = friendObj.getString(Sid);
											friend = friendObj.getString(SdisplayName);
											String originalContent = object.getString(SoriginalContent);
											if ((originalContent == null) || (originalContent.length() == 0))
												originalContent = object.getString(Scontent);
											String photo = null;
											if (display_profile && friendObj.has(Simage)) {
												JSONObject image = friendObj.getJSONObject(Simage);
												if (image.has(Surl))
													photo = image.getString(Surl);
											}
											long date = parseDate(statusObj.getString(Spublished), GOOGLEPLUS_DATE_FORMAT);
											int commentCount = 0;
											JSONObject replies = object.getJSONObject(Sreplies);
											String notification = null;
											if (replies.has(StotalItems))
												commentCount = replies.getInt(StotalItems);
											if ((notifications != 0) && (notification != null)) {
												// new notification
												addNotification(sid, esid, friend, originalContent, date, account, notification);
											}
											if (i1 < status_count) {
												addStatusItem(date,
														friend,
														photo,
														String.format(getString(R.string.messageWithCommentCount), originalContent, commentCount),
														service,
														time24hr,
														appWidgetId,
														account,
														sid,
														esid,
														links,
														httpClient);
											}
										}
									}
								}
							}
						}
					}
				}
			} catch (UnsupportedEncodingException e) {
				Log.e(TAG,e.toString());
			} catch (JSONException e) {
				Log.e(TAG,e.toString());
			}
		}

		private void updatePinterest(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			// parse the response
			if ((response = SonetHttpClient.httpResponse(httpClient, new HttpGet(String.format(PINTEREST_URL_FEED, PINTEREST_BASE_URL)))) != null) {
				// if not a full_refresh, only update the status_bg and icons
				try {
					JSONObject pins = new JSONObject(response);
					if (pins.has("pins")) {
						statusesArray = pins.getJSONArray("pins");
						// if there are updates, clear the cache
						int e2 = statusesArray.length();
						if (e2 > 0) {
							removeOldStatuses(widget, Long.toString(account));
							for (int e = 0; e < e2; e++) {
								links.clear();
								statusObj = statusesArray.getJSONObject(e);
								friendObj = statusObj.getJSONObject(Suser);
								long date = parseDate(statusObj.getString(Screated_at), PINTEREST_DATE_FORMAT);
								int commentCount = 0;
								if (statusObj.has(Scounts)) {
									JSONObject counts = statusObj.getJSONObject(Scounts);
									if (counts.has(Scomments))
										commentCount = counts.getInt(Scomments);
								}
								if (statusObj.has(Simages)) {
									JSONObject images = statusObj.getJSONObject(Simages);
									if (images.has(Smobile))
										links.add(new String[]{Simage, images.getString(Smobile)});
									else if (images.has(Sboard))
										links.add(new String[]{Simage, images.getString(Sboard)});
								}
								addStatusItem(date,
										friendObj.getString(Susername),
										display_profile ? friendObj.getString(Simage_url) : null,
												String.format(getString(R.string.messageWithCommentCount), statusObj.getString(Sdescription), commentCount),
												service,
												time24hr,
												appWidgetId,
												account,
												statusObj.getString(Sid),
												friendObj.getString(Sid),
												links,
												httpClient);
							}
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
				}
			}
		}

		private void updateChatter(String token, String secret, String accountEsid, int appWidgetId, String widget, long account, int service, int status_count, boolean time24hr, boolean display_profile, int notifications, HttpClient httpClient) {
			String response;
			JSONArray statusesArray;
			ArrayList<String[]> links = new ArrayList<String[]>();
			HttpGet httpGet;
			mSimpleDateFormat = null;
			JSONObject statusObj;
			JSONObject friendObj;
			// need to get an updated access_token
			String accessResponse = SonetHttpClient.httpResponse(httpClient, new HttpPost(String.format(CHATTER_URL_ACCESS, BuildConfig.CHATTER_KEY, token)));
			if (accessResponse != null) {
				try {
					JSONObject jobj = new JSONObject(accessResponse);
					if (jobj.has(Sinstance_url) && jobj.has(Saccess_token)) {
						httpGet = new HttpGet(String.format(CHATTER_URL_FEED, jobj.getString(Sinstance_url)));
						String chatterToken = jobj.getString(Saccess_token);
						httpGet.setHeader("Authorization", "OAuth " + chatterToken);
						if ((response = SonetHttpClient.httpResponse(httpClient, httpGet)) != null) {
							try {
								statusesArray = new JSONObject(response).getJSONArray(Sitems);
								// if there are updates, clear the cache
								int e2 = statusesArray.length();
								if (e2 > 0) {
									removeOldStatuses(widget, Long.toString(account));
									for (int e = 0; (e < e2) && (e < status_count); e++) {
										links.clear();
										statusObj = statusesArray.getJSONObject(e);
										friendObj = statusObj.getJSONObject(Suser);
										JSONObject photo = friendObj.getJSONObject(Sphoto);
										JSONObject comments = statusObj.getJSONObject(Scomments);
										long date = parseDate(statusObj.getString(ScreatedDate), CHATTER_DATE_FORMAT);
										if (e < status_count) {
											addStatusItem(date,
													friendObj.getString(Sname),
													display_profile ? photo.getString(SsmallPhotoUrl) + "?oauth_token=" + chatterToken : null,
															String.format(getString(R.string.messageWithCommentCount), statusObj.getJSONObject(Sbody).getString(Stext), comments.getInt(Stotal)),
															service,
															time24hr,
															appWidgetId,
															account,
															statusObj.getString(Sid),
															friendObj.getString(Sid),
															links,
															httpClient);
										}
									}
								}
							} catch (JSONException e) {
								Log.e(TAG, service + ":" + e.toString());
								Log.e(TAG, response);
							}
						}
					}
				} catch (JSONException e) {
					Log.e(TAG, service + ":" + e.toString());
					Log.e(TAG, accessResponse);
				}
			}
		}
	}

	private void buildWidgetButtons(Integer appWidgetId, boolean updatesReady, int page, boolean hasbuttons, int scrollable, int buttons_bg_color, int buttons_color, int buttons_textsize, boolean display_profile, int margin) {
		final String widget = Integer.toString(appWidgetId);
		// Push update for this widget to the home screen
		int layout;
		if (hasbuttons) {
			if (sNativeScrollingSupported) {
				if (margin > 0)
					layout = R.layout.widget_margin_scrollable;
				else
					layout = R.layout.widget_scrollable;
			} else if (display_profile) {
				if (margin > 0)
					layout = R.layout.widget_margin;
				else
					layout = R.layout.widget;
			} else {
				if (margin > 0)
					layout = R.layout.widget_noprofile_margin;
				else
					layout = R.layout.widget_noprofile;
			}
		} else {
			if (sNativeScrollingSupported) {
				if (margin > 0)
					layout = R.layout.widget_nobuttons_margin_scrollable;
				else
					layout = R.layout.widget_nobuttons_scrollable;
			} else if (display_profile) {
				if (margin > 0)
					layout = R.layout.widget_nobuttons_margin;
				else
					layout = R.layout.widget_nobuttons;
			} else {
				if (margin > 0)
					layout = R.layout.widget_nobuttons_noprofile_margin;
				else
					layout = R.layout.widget_nobuttons_noprofile;
			}
		}
		// wrap RemoteViews for backward compatibility
		RemoteViews views = new RemoteViews(getPackageName(), layout);
		if (hasbuttons) {
			Bitmap buttons_bg = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
			Canvas buttons_bg_canvas = new Canvas(buttons_bg);
			buttons_bg_canvas.drawColor(buttons_bg_color);
			views.setImageViewBitmap(R.id.buttons_bg, buttons_bg);
			views.setTextColor(R.id.buttons_bg_clear, buttons_bg_color);
			views.setFloat(R.id.buttons_bg_clear, "setTextSize", buttons_textsize);
			views.setOnClickPendingIntent(R.id.button_post, PendingIntent.getActivity(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetCreatePost.class).setAction(LauncherIntent.Action.ACTION_VIEW_CLICK).setData(Uri.withAppendedPath(Widgets.getContentUri(SonetService.this), widget)), 0));
			views.setTextColor(R.id.button_post, buttons_color);
			views.setFloat(R.id.button_post, "setTextSize", buttons_textsize);
			views.setOnClickPendingIntent(R.id.button_configure, PendingIntent.getActivity(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, ManageAccounts.class).setAction(widget), 0));
			views.setTextColor(R.id.button_configure, buttons_color);
			views.setFloat(R.id.button_configure, "setTextSize", buttons_textsize);
			views.setOnClickPendingIntent(R.id.button_refresh, PendingIntent.getService(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetService.class).setAction(widget), 0));
			views.setTextColor(R.id.button_refresh, buttons_color);
			views.setFloat(R.id.button_refresh, "setTextSize", buttons_textsize);
			views.setTextColor(R.id.page_up, buttons_color);
			views.setFloat(R.id.page_up, "setTextSize", buttons_textsize);
			views.setTextColor(R.id.page_down, buttons_color);
			views.setFloat(R.id.page_down, "setTextSize", buttons_textsize);
		}
		// set margin
		if (scrollable == 0) {
			final AppWidgetManager mgr = AppWidgetManager.getInstance(SonetService.this);
			// check if native scrolling is supported
			if (sNativeScrollingSupported) {
				// native scrolling
				try {
					final Intent intent = SonetRemoteViewsServiceWrapper.getRemoteAdapterIntent(SonetService.this);
					if (intent != null) {
						intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
						intent.putExtra(Widgets.DISPLAY_PROFILE, display_profile);
						intent.setData(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)));
						sSetRemoteAdapter.invoke(views, appWidgetId, R.id.messages, intent);
						// empty
						sSetEmptyView.invoke(views, R.id.messages, R.id.empty_messages);
						// onclick
						// Bind a click listener template for the contents of the message list
						final Intent onClickIntent = Sonet.getPackageIntent(SonetService.this, SonetWidget.class);
						onClickIntent.setAction(ACTION_ON_CLICK);
						onClickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
						onClickIntent.setData(Uri.parse(onClickIntent.toUri(Intent.URI_INTENT_SCHEME)));
						final PendingIntent onClickPendingIntent = PendingIntent.getBroadcast(SonetService.this, 0, onClickIntent, PendingIntent.FLAG_UPDATE_CURRENT);
						sSetPendingIntentTemplate.invoke(views, R.id.messages, onClickPendingIntent);
					} else {
						// fallback on non-scrolling widget
						sNativeScrollingSupported = false;
					}
				} catch (NumberFormatException e) {
					Log.e(TAG, e.toString());
				} catch (IllegalArgumentException e) {
					Log.e(TAG, e.toString());
				} catch (IllegalAccessException e) {
					Log.e(TAG, e.toString());
				} catch (InvocationTargetException e) {
					Log.e(TAG, e.toString());
				}

			}
			if (!sNativeScrollingSupported) {
				Cursor statuses_styles = getContentResolver().query(Uri.withAppendedPath(Statuses_styles.getContentUri(SonetService.this), widget), new String[]{Statuses_styles._ID, Statuses_styles.FRIEND, Statuses_styles.PROFILE, Statuses_styles.MESSAGE, Statuses_styles.CREATEDTEXT, Statuses_styles.MESSAGES_COLOR, Statuses_styles.FRIEND_COLOR, Statuses_styles.CREATED_COLOR, Statuses_styles.MESSAGES_TEXTSIZE, Statuses_styles.FRIEND_TEXTSIZE, Statuses_styles.CREATED_TEXTSIZE, Statuses_styles.STATUS_BG, Statuses_styles.ICON, Statuses_styles.PROFILE_BG, Statuses_styles.FRIEND_BG, Statuses_styles.IMAGE_BG, Statuses_styles.IMAGE}, null, null, Statuses_styles.CREATED + " DESC LIMIT " + page + ",-1");
				if (statuses_styles.moveToFirst()) {
					int count_status = 0;
					views.removeAllViews(R.id.messages);
					while (!statuses_styles.isAfterLast() && (count_status < 16)) {
						int friend_color = statuses_styles.getInt(6),
								created_color = statuses_styles.getInt(7),
								friend_textsize = statuses_styles.getInt(9),
								created_textsize = statuses_styles.getInt(10),
								messages_color = statuses_styles.getInt(5),
								messages_textsize = statuses_styles.getInt(8);
						// get the item wrapper
						RemoteViews itemView;
						if (display_profile) {
							itemView = new RemoteViews(getPackageName(), R.layout.widget_item);
							// set profiles background
							byte[] profile_bg = statuses_styles.getBlob(13);
							if (profile_bg != null) {
								Bitmap profile_bgbmp = BitmapFactory.decodeByteArray(profile_bg, 0, profile_bg.length, sBFOptions);
								if (profile_bgbmp != null)
									itemView.setImageViewBitmap(R.id.profile_bg, profile_bgbmp);
							}
							byte[] profile = statuses_styles.getBlob(2);
							if (profile != null) {
								Bitmap profilebmp = BitmapFactory.decodeByteArray(profile, 0, profile.length, sBFOptions);
								if (profilebmp != null)
									itemView.setImageViewBitmap(R.id.profile, profilebmp);
							}
						} else
							itemView = new RemoteViews(getPackageName(), R.layout.widget_item_noprofile);
						itemView.setTextViewText(R.id.friend_bg_clear, statuses_styles.getString(1));
						itemView.setFloat(R.id.friend_bg_clear, "setTextSize", friend_textsize);
						itemView.setTextViewText(R.id.message_bg_clear, statuses_styles.getString(3));
						itemView.setFloat(R.id.message_bg_clear, "setTextSize", messages_textsize);
						// set friends background
						byte[] friend_bg = statuses_styles.getBlob(14);
						if (friend_bg != null) {
							Bitmap friend_bgbmp = BitmapFactory.decodeByteArray(friend_bg, 0, friend_bg.length, sBFOptions);
							if (friend_bgbmp != null)
								itemView.setImageViewBitmap(R.id.friend_bg, friend_bgbmp);
						}
						// set messages background
						byte[] status_bg = statuses_styles.getBlob(11);
						if (status_bg != null) {
							Bitmap status_bgbmp = BitmapFactory.decodeByteArray(status_bg, 0, status_bg.length, sBFOptions);
							if (status_bgbmp != null)
								itemView.setImageViewBitmap(R.id.status_bg, status_bgbmp);
						}
						// set an image
						byte[] image_bg = statuses_styles.getBlob(15);
						byte[] image = statuses_styles.getBlob(16);
						if ((image_bg != null) && (image != null)) {
							Bitmap image_bgBmp = BitmapFactory.decodeByteArray(image_bg, 0, image_bg.length, sBFOptions);
							if (image_bgBmp != null) {
								Bitmap imageBmp = BitmapFactory.decodeByteArray(image, 0, image.length, sBFOptions);
								itemView.setImageViewBitmap(R.id.image_clear, image_bgBmp);
								itemView.setImageViewBitmap(R.id.image, imageBmp);
							}
						}
						itemView.setTextViewText(R.id.message, statuses_styles.getString(3));
						itemView.setTextColor(R.id.message, messages_color);
						itemView.setFloat(R.id.message, "setTextSize", messages_textsize);
						itemView.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, StatusDialog.class).setData(Uri.withAppendedPath(Statuses_styles.getContentUri(SonetService.this), Long.toString(statuses_styles.getLong(0)))), 0));
						itemView.setTextViewText(R.id.friend, statuses_styles.getString(1));
						itemView.setTextColor(R.id.friend, friend_color);
						itemView.setFloat(R.id.friend, "setTextSize", friend_textsize);
						itemView.setTextViewText(R.id.created, statuses_styles.getString(4));
						itemView.setTextColor(R.id.created, created_color);
						itemView.setFloat(R.id.created, "setTextSize", created_textsize);
						// set icons
						byte[] icon = statuses_styles.getBlob(12);
						if (icon != null) {
							Bitmap iconbmp = BitmapFactory.decodeByteArray(icon, 0, icon.length, sBFOptions);
							if (iconbmp != null)
								itemView.setImageViewBitmap(R.id.icon, iconbmp);
						}
						views.addView(R.id.messages, itemView);
						count_status++;
						statuses_styles.moveToNext();
					}
					if (hasbuttons && (page < statuses_styles.getCount())) {
						// there are more statuses to show, allow paging down
						views.setOnClickPendingIntent(R.id.page_down, PendingIntent.getService(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetService.class).setAction(ACTION_PAGE_DOWN).setData(Uri.withAppendedPath(Widgets.getContentUri(SonetService.this), widget)).putExtra(ACTION_PAGE_DOWN, page + 1), PendingIntent.FLAG_UPDATE_CURRENT));
					}
				}
				statuses_styles.close();
				if (hasbuttons && (page > 0))
					views.setOnClickPendingIntent(R.id.page_up, PendingIntent.getService(SonetService.this, 0, Sonet.getPackageIntent(SonetService.this, SonetService.class).setAction(ACTION_PAGE_UP).setData(Uri.withAppendedPath(Widgets.getContentUri(SonetService.this), widget)).putExtra(ACTION_PAGE_UP, page - 1), PendingIntent.FLAG_UPDATE_CURRENT));
			}
			Log.d(TAG, "update native widget: " + appWidgetId);
			mgr.updateAppWidget(appWidgetId, views);
			if (sNativeScrollingSupported) {
				Log.d(TAG, "trigger widget query: " + appWidgetId);
				try {
					// trigger query
					sNotifyAppWidgetViewDataChanged.invoke(mgr, appWidgetId, R.id.messages);
				} catch (NumberFormatException e) {
					Log.e(TAG, e.toString());
				} catch (IllegalArgumentException e) {
					Log.e(TAG, e.toString());
				} catch (IllegalAccessException e) {
					Log.e(TAG, e.toString());
				} catch (InvocationTargetException e) {
					Log.e(TAG, e.toString());
				}
			}
		} else if (updatesReady) {
			//			Log.d(TAG, "notify updatesReady");
			getContentResolver().notifyChange(Statuses_styles.getContentUri(SonetService.this), null);
		} else {
			AppWidgetManager.getInstance(SonetService.this).updateAppWidget(Integer.parseInt(widget), views);
			buildScrollableWidget(appWidgetId, scrollable, display_profile);
		}
	}

	private void buildScrollableWidget(Integer appWidgetId, int scrollableVersion, boolean display_profile) {
		// set widget as scrollable
		Intent replaceDummy = new Intent(LauncherIntent.Action.ACTION_SCROLL_WIDGET_START);
		replaceDummy.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
		replaceDummy.putExtra(LauncherIntent.Extra.EXTRA_VIEW_ID, R.id.messages);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_LISTVIEW_LAYOUT_ID, R.layout.widget_listview);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_PROVIDER_ALLOW_REQUERY, true);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_CHILDREN_CLICKABLE, true);

		//provider
		Uri uri = Uri.withAppendedPath(Statuses_styles.getContentUri(SonetService.this), Integer.toString(appWidgetId));
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_DATA_URI, uri.toString());
		String[] projection;
		if (display_profile)
			projection = new String[]{Statuses_styles._ID, Statuses_styles.FRIEND, Statuses_styles.PROFILE, Statuses_styles.MESSAGE, Statuses_styles.CREATEDTEXT, Statuses_styles.MESSAGES_COLOR, Statuses_styles.FRIEND_COLOR, Statuses_styles.CREATED_COLOR, Statuses_styles.MESSAGES_TEXTSIZE, Statuses_styles.FRIEND_TEXTSIZE, Statuses_styles.CREATED_TEXTSIZE, Statuses_styles.STATUS_BG, Statuses_styles.ICON, Statuses_styles.PROFILE_BG, Statuses_styles.FRIEND_BG, Statuses_styles.IMAGE_BG, Statuses_styles.IMAGE};
		else
			projection = new String[]{Statuses_styles._ID, Statuses_styles.FRIEND, Statuses_styles.PROFILE, Statuses_styles.MESSAGE, Statuses_styles.CREATEDTEXT, Statuses_styles.MESSAGES_COLOR, Statuses_styles.FRIEND_COLOR, Statuses_styles.CREATED_COLOR, Statuses_styles.MESSAGES_TEXTSIZE, Statuses_styles.FRIEND_TEXTSIZE, Statuses_styles.CREATED_TEXTSIZE, Statuses_styles.STATUS_BG, Statuses_styles.ICON, Statuses_styles.FRIEND_BG, Statuses_styles.IMAGE_BG, Statuses_styles.IMAGE};
		String sortOrder = Statuses_styles.CREATED + " DESC";
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_PROJECTION, projection);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_SORT_ORDER, sortOrder);
		String whereClause = Statuses_styles.WIDGET + "=?";
		String[] selectionArgs = new String[]{Integer.toString(appWidgetId)};
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION, whereClause);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_SELECTION_ARGUMENTS, selectionArgs);
		replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_ACTION_VIEW_URI_INDEX, SonetProvider.StatusesStylesColumns._id.ordinal());

		switch (scrollableVersion) {
		case 1:
			if (display_profile) {
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_ID, R.layout.widget_item);
				int[] cursorIndices = new int[]{SonetProvider.StatusesStylesColumns.friend.ordinal(),
						SonetProvider.StatusesStylesColumns.message.ordinal(),
						SonetProvider.StatusesStylesColumns.status_bg.ordinal(),
						SonetProvider.StatusesStylesColumns.profile.ordinal(),
						SonetProvider.StatusesStylesColumns.friend.ordinal(),
						SonetProvider.StatusesStylesColumns.createdtext.ordinal(),
						SonetProvider.StatusesStylesColumns.message.ordinal(),
						SonetProvider.StatusesStylesColumns.icon.ordinal(),
						SonetProvider.StatusesStylesColumns.profile_bg.ordinal(),
						SonetProvider.StatusesStylesColumns.friend_bg.ordinal(),
						SonetProvider.StatusesStylesColumns.image_bg.ordinal(),
						SonetProvider.StatusesStylesColumns.image.ordinal()};
				int[] viewTypes = new int[]{LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB};
				int[] layoutIds = new int[]{R.id.friend_bg_clear,
						R.id.message_bg_clear,
						R.id.status_bg,
						R.id.profile,
						R.id.friend,
						R.id.created,
						R.id.message,
						R.id.icon,
						R.id.profile_bg,
						R.id.friend_bg,
						R.id.image_clear,
						R.id.image};
				int[] defaultResource = new int[]{0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0};
				boolean[] clickable = new boolean[]{false,
						false,
						true,
						false,
						false,
						false,
						false,
						false,
						false,
						false,
						false,
						false};
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_CURSOR_INDICES, cursorIndices);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_TYPES, viewTypes);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_IDS, layoutIds);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_DEFAULT_RESOURCES, defaultResource);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_CLICKABLE, clickable);			
			} else {
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_ID, R.layout.widget_item_noprofile);
				int[] cursorIndices = new int[]{SonetProvider.StatusesStylesColumnsNoProfile.friend.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.message.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.status_bg.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.friend.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.createdtext.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.message.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.icon.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.friend_bg.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.image_bg.ordinal(),
						SonetProvider.StatusesStylesColumnsNoProfile.image.ordinal()};
				int[] viewTypes = new int[]{LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.TEXTVIEW,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB,
						LauncherIntent.Extra.Scroll.Types.IMAGEBLOB};
				int[] layoutIds = new int[]{R.id.friend_bg_clear,
						R.id.message_bg_clear,
						R.id.status_bg,
						R.id.friend,
						R.id.created,
						R.id.message,
						R.id.icon,
						R.id.friend_bg,
						R.id.image_clear,
						R.id.image};
				int[] defaultResource = new int[]{0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0,
						0};
				boolean[] clickable = new boolean[]{false,
						false,
						true,
						false,
						false,
						false,
						false,
						false,
						false,
						false};
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_CURSOR_INDICES, cursorIndices);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_TYPES, viewTypes);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_IDS, layoutIds);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_DEFAULT_RESOURCES, defaultResource);
				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.Mapping.EXTRA_VIEW_CLICKABLE, clickable);
			}
			break;
		case 2:
			if (display_profile) {
				BoundRemoteViews itemViews = new BoundRemoteViews(R.layout.widget_item);

				Intent i = Sonet.getPackageIntent(SonetService.this, SonetWidget.class)
						.setAction(LauncherIntent.Action.ACTION_VIEW_CLICK)
						.setData(uri)
						.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
				PendingIntent pi = PendingIntent.getBroadcast(SonetService.this, 0, i, 0);

				itemViews.SetBoundOnClickIntent(R.id.item, pi, LauncherIntent.Extra.Scroll.EXTRA_ITEM_POS, SonetProvider.StatusesStylesColumns._id.ordinal());

				itemViews.setBoundCharSequence(R.id.friend_bg_clear, "setText", SonetProvider.StatusesStylesColumns.friend.ordinal(), 0);
				itemViews.setBoundFloat(R.id.friend_bg_clear, "setTextSize", SonetProvider.StatusesStylesColumns.friend_textsize.ordinal());

				itemViews.setBoundCharSequence(R.id.message_bg_clear, "setText", SonetProvider.StatusesStylesColumns.message.ordinal(), 0);
				itemViews.setBoundFloat(R.id.message_bg_clear, "setTextSize", SonetProvider.StatusesStylesColumns.messages_textsize.ordinal());

				itemViews.setBoundBitmap(R.id.status_bg, "setImageBitmap", SonetProvider.StatusesStylesColumns.status_bg.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.image_clear, "setImageBitmap", SonetProvider.StatusesStylesColumns.image_bg.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.image, "setImageBitmap", SonetProvider.StatusesStylesColumns.image.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.profile, "setImageBitmap", SonetProvider.StatusesStylesColumns.profile.ordinal(), 0);
				itemViews.setBoundCharSequence(R.id.friend, "setText", SonetProvider.StatusesStylesColumns.friend.ordinal(), 0);
				itemViews.setBoundCharSequence(R.id.created, "setText", SonetProvider.StatusesStylesColumns.createdtext.ordinal(), 0);
				itemViews.setBoundCharSequence(R.id.message, "setText", SonetProvider.StatusesStylesColumns.message.ordinal(), 0);

				itemViews.setBoundInt(R.id.friend, "setTextColor", SonetProvider.StatusesStylesColumns.friend_color.ordinal());
				itemViews.setBoundInt(R.id.created, "setTextColor", SonetProvider.StatusesStylesColumns.created_color.ordinal());
				itemViews.setBoundInt(R.id.message, "setTextColor", SonetProvider.StatusesStylesColumns.messages_color.ordinal());



				itemViews.setBoundFloat(R.id.friend, "setTextSize", SonetProvider.StatusesStylesColumns.friend_textsize.ordinal());
				itemViews.setBoundFloat(R.id.created, "setTextSize", SonetProvider.StatusesStylesColumns.created_textsize.ordinal());
				itemViews.setBoundFloat(R.id.message, "setTextSize", SonetProvider.StatusesStylesColumns.messages_textsize.ordinal());

				itemViews.setBoundBitmap(R.id.icon, "setImageBitmap", SonetProvider.StatusesStylesColumns.icon.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.profile_bg, "setImageBitmap", SonetProvider.StatusesStylesColumns.profile_bg.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.friend_bg, "setImageBitmap", SonetProvider.StatusesStylesColumns.friend_bg.ordinal(), 0);

				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_REMOTEVIEWS, itemViews);
			} else {
				BoundRemoteViews itemViews = new BoundRemoteViews(R.layout.widget_item_noprofile);

				Intent i = Sonet.getPackageIntent(SonetService.this, SonetWidget.class)
						.setAction(LauncherIntent.Action.ACTION_VIEW_CLICK)
						.setData(uri)
						.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
				PendingIntent pi = PendingIntent.getBroadcast(SonetService.this, 0, i, 0);

				itemViews.SetBoundOnClickIntent(R.id.item, pi, LauncherIntent.Extra.Scroll.EXTRA_ITEM_POS, SonetProvider.StatusesStylesColumnsNoProfile._id.ordinal());

				itemViews.setBoundCharSequence(R.id.friend_bg_clear, "setText", SonetProvider.StatusesStylesColumnsNoProfile.friend.ordinal(), 0);
				itemViews.setBoundFloat(R.id.friend_bg_clear, "setTextSize", SonetProvider.StatusesStylesColumnsNoProfile.friend_textsize.ordinal());

				itemViews.setBoundCharSequence(R.id.message_bg_clear, "setText", SonetProvider.StatusesStylesColumnsNoProfile.message.ordinal(), 0);
				itemViews.setBoundFloat(R.id.message_bg_clear, "setTextSize", SonetProvider.StatusesStylesColumnsNoProfile.messages_textsize.ordinal());

				itemViews.setBoundBitmap(R.id.status_bg, "setImageBitmap", SonetProvider.StatusesStylesColumnsNoProfile.status_bg.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.image_clear, "setImageBitmap", SonetProvider.StatusesStylesColumnsNoProfile.image_bg.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.image, "setImageBitmap", SonetProvider.StatusesStylesColumnsNoProfile.image.ordinal(), 0);

				itemViews.setBoundCharSequence(R.id.friend, "setText", SonetProvider.StatusesStylesColumnsNoProfile.friend.ordinal(), 0);
				itemViews.setBoundCharSequence(R.id.created, "setText", SonetProvider.StatusesStylesColumnsNoProfile.createdtext.ordinal(), 0);
				itemViews.setBoundCharSequence(R.id.message, "setText", SonetProvider.StatusesStylesColumnsNoProfile.message.ordinal(), 0);

				itemViews.setBoundInt(R.id.friend, "setTextColor", SonetProvider.StatusesStylesColumnsNoProfile.friend_color.ordinal());
				itemViews.setBoundInt(R.id.created, "setTextColor", SonetProvider.StatusesStylesColumnsNoProfile.created_color.ordinal());
				itemViews.setBoundInt(R.id.message, "setTextColor", SonetProvider.StatusesStylesColumnsNoProfile.messages_color.ordinal());

				itemViews.setBoundFloat(R.id.friend, "setTextSize", SonetProvider.StatusesStylesColumnsNoProfile.friend_textsize.ordinal());
				itemViews.setBoundFloat(R.id.created, "setTextSize", SonetProvider.StatusesStylesColumnsNoProfile.created_textsize.ordinal());
				itemViews.setBoundFloat(R.id.message, "setTextSize", SonetProvider.StatusesStylesColumnsNoProfile.messages_textsize.ordinal());

				itemViews.setBoundBitmap(R.id.icon, "setImageBitmap", SonetProvider.StatusesStylesColumnsNoProfile.icon.ordinal(), 0);

				itemViews.setBoundBitmap(R.id.friend_bg, "setImageBitmap", SonetProvider.StatusesStylesColumnsNoProfile.friend_bg.ordinal(), 0);

				replaceDummy.putExtra(LauncherIntent.Extra.Scroll.EXTRA_ITEM_LAYOUT_REMOTEVIEWS, itemViews);
			}
			break;
		}
		sendBroadcast(replaceDummy);
	}
}
