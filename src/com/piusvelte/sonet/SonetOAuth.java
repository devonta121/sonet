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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.DefaultHttpClient;

import android.util.Log;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthProvider;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;
import oauth.signpost.exception.OAuthNotAuthorizedException;
import oauth.signpost.signature.HmacSha1MessageSigner;

public class SonetOAuth {

	private OAuthConsumer mOAuthConsumer;
	private OAuthProvider mOAuthProvider;
	private static final String TAG = "SonetOAuth";

	public SonetOAuth(String apiKey, String apiSecret) {
		mOAuthConsumer = new CommonsHttpOAuthConsumer(apiKey, apiSecret);
		mOAuthConsumer.setMessageSigner(new HmacSha1MessageSigner());
	}

	public SonetOAuth(String apiKey, String apiSecret, String token, String tokenSecret) {
		mOAuthConsumer = new CommonsHttpOAuthConsumer(apiKey, apiSecret);
		mOAuthConsumer.setMessageSigner(new HmacSha1MessageSigner());
		mOAuthConsumer.setTokenWithSecret(token, tokenSecret);
	}

	public String getAuthUrl(String request, String access, String authorize, String callback, boolean isOAuth10a) {
		mOAuthProvider = new CommonsHttpOAuthProvider(request, access, authorize);
		mOAuthProvider.setOAuth10a(isOAuth10a);
		try {
			return mOAuthProvider.retrieveRequestToken(mOAuthConsumer, callback);
		} catch (OAuthMessageSignerException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthNotAuthorizedException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthExpectationFailedException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthCommunicationException e) {
			Log.e(TAG,e.toString());
		}
		return null;
	}

	public boolean retrieveAccessToken(String verifier) {
		try {
			mOAuthProvider.retrieveAccessToken(mOAuthConsumer, verifier);
			return true;
		} catch (OAuthMessageSignerException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthNotAuthorizedException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthExpectationFailedException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthCommunicationException e) {
			Log.e(TAG,e.toString());
		}
		return false;
	}

	public String httpResponse(HttpUriRequest httpRequest) {
		String response = null;
		try {
			mOAuthConsumer.sign(httpRequest);
			HttpClient httpClient = new DefaultHttpClient();
			HttpResponse httpResponse = httpClient.execute(httpRequest);
			StatusLine statusLine = httpResponse.getStatusLine();
			HttpEntity entity = httpResponse.getEntity();

			switch(statusLine.getStatusCode()) {
			case 200:
			case 201:
				if (entity != null) {
					InputStream is = entity.getContent();
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));
					StringBuilder sb = new StringBuilder();

					String line = null;
					try {
						while ((line = reader.readLine()) != null) sb.append(line + "\n");
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						try {
							is.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					response = sb.toString();
				}
				break;
			default:
				Log.e(TAG,httpRequest.getURI().toString());
				Log.e(TAG,""+statusLine.getStatusCode()+" "+statusLine.getReasonPhrase());
				if (entity != null) {
					InputStream is = entity.getContent();
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));
					StringBuilder sb = new StringBuilder();

					String line = null;
					try {
						while ((line = reader.readLine()) != null) sb.append(line + "\n");
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						try {
							is.close();
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
					Log.e(TAG,"response:"+sb.toString());
				}
				break;
			}
		} catch (OAuthMessageSignerException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthExpectationFailedException e) {
			Log.e(TAG,e.toString());
		} catch (OAuthCommunicationException e) {
			Log.e(TAG,e.toString());
		} catch (ClientProtocolException e) {
			Log.e(TAG,e.toString());
		} catch (IOException e) {
			Log.e(TAG,e.toString());
		}
		return response;		
	}

	public String getToken() {
		return mOAuthConsumer.getToken();
	}

	public String getTokenSecret() {
		return mOAuthConsumer.getTokenSecret();
	}

}
