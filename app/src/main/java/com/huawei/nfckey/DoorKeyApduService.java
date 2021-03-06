package com.huawei.nfckey;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.Toast;


import org.spongycastle.jce.ECNamedCurveTable;
import org.spongycastle.jce.provider.BouncyCastleProvider;
import org.spongycastle.jce.spec.ECParameterSpec;
import org.spongycastle.openssl.jcajce.JcaPEMWriter;
import org.spongycastle.util.io.pem.PemWriter;
import org.spongycastle.x509.X509V3CertificateGenerator;


import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.AlgorithmParameters;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.X509Certificate;

import java.util.Calendar;
import java.util.Date;

import javax.security.auth.x500.X500Principal;

public class DoorKeyApduService extends HostApduService {

    private static final String TAG = "NFCKey";
    public static int MAX_FRAME = 250;

    //
    // We use the default AID from the HCE Android documentation
    // https://developer.android.com/guide/topics/connectivity/nfc/hce.html
    //
    // Ala... <aid-filter android:name="F0394148148100" />
    //
    private static final byte[] APDU_SELECT = {
            (byte)0x00, // CLA	- Class - Class of instruction
            (byte)0xA4, // INS	- Instruction - Instruction code
            (byte)0x04, // P1	- Parameter 1 - Instruction parameter 1
            (byte)0x00, // P2	- Parameter 2 - Instruction parameter 2
            (byte)0x07, // Lc field	- Number of bytes present in the data field of the command
            (byte)0xF0, (byte)0xA9, (byte)0x41, (byte)0x48, (byte)0x14, (byte)0x81, (byte)0x00, // Key Tag Application name
            (byte)0x00  // Le field	- Maximum number of bytes expected in the data field of the response to the command
    };

    private static final byte[] DOOR_IDENT = {
            (byte)0x00,
            (byte)0xe0,
            (byte)0x00,
            (byte)0x00
    };

    private static final byte[] DOOR_IDENT_FRAGMENT = {
            (byte)0x00,
            (byte)0xe0,
            (byte)0x01,
    };

    private static final byte[] DOOR_CHALLENGE = {
            (byte)0x00,
            (byte)0xe0,
            (byte)0x02,
            (byte)0x00
    };

    private static final byte[] A_OKAY = {
            (byte)0x90,  // SW1	Status byte 1 - Command processing status
            (byte)0x00   // SW2	Status byte 2 - Command processing qualifier
    };

    private X509Certificate certificate;


    @Override
    public void onCreate() {
        try {
            Log.i(TAG, "Load Cert");
            FileInputStream fis = openFileInput("session.pem");
            Security.addProvider(new org.spongycastle.jce.provider.BouncyCastleProvider());
            CertificateFactory cf = CertificateFactory.getInstance("X.509", "SC");
            certificate = (X509Certificate) cf.generateCertificate((InputStream) fis);
            fis.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load keys: " + e.getMessage());
        }
        Log.e(TAG, "Keys loaded!");
    }

    public byte[] signChallenge(byte[] challenge) throws Exception
    {
        FileInputStream fis = openFileInput("session.key");
        int size = (int)fis.getChannel().size();
        byte[] key = new byte[size];
        fis.read(key);
        fis.close();

        KeyFactory keyFactory = KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm(), "SC");

        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(key);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        Signature s = Signature.getInstance(certificate.getSigAlgName(), "SC");
        s.initSign(privateKey);
        s.update(challenge);
        return s.sign();
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return 0;
    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        try {

            //
            // The following flow is based on Appendix E "Example of Mapping Version 2.0 Command Flow"
            // in the NFC Forum specification
            //
            Log.i(TAG, "processCommandApdu() | incoming commandApdu: " + utils.bytesToHex(commandApdu));

            //
            // First command: NDEF Tag Application select (Section 5.5.2 in NFC Forum spec)
            //
            if (utils.isEqual(APDU_SELECT, commandApdu)) {
                Log.i(TAG, "APDU_SELECT triggered. Our Response: " + utils.bytesToHex(A_OKAY));
                return A_OKAY;
            }

            // Lock identifies key
            if (utils.isEqual(DOOR_IDENT, commandApdu)) {
                byte[] start = {
                        (byte) 0x00
                };

                // Build our response
                byte[] response = new byte[start.length + 2 * 4 + A_OKAY.length];
                // Certificate and fragment size array
                byte[] certlen = ByteBuffer.allocate(2 * 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(MAX_FRAME)
                        .putInt(certificate.getEncoded().length)
                        .array();


                System.arraycopy(start, 0, response, 0, start.length);
                System.arraycopy(certlen, 0, response, start.length, certlen.length);
                System.arraycopy(A_OKAY, 0, response, start.length + certlen.length, A_OKAY.length);

                Log.i(TAG, "Lock ident! Our Response: " + utils.bytesToHex(response));

                return response;
            }

            // Lock want to read cert fragment
            if (utils.startsWith(DOOR_IDENT_FRAGMENT, commandApdu)) {
                int frag = commandApdu[3];

                byte[] certificateBuffer = certificate.getEncoded();

                int len = MAX_FRAME;

                if ((frag + 1) * MAX_FRAME > certificateBuffer.length)
                    len = certificateBuffer.length - (frag * MAX_FRAME);

                byte[] response = new byte[len + A_OKAY.length];


                System.arraycopy(certificateBuffer, frag * MAX_FRAME, response, 0, len);
                System.arraycopy(A_OKAY, 0, response, len, A_OKAY.length);

                Log.i(TAG, String.format("Lock wants to read cert fragment %d", frag));
                Log.i(TAG, "Lock wants to read cert. Our Response: " + utils.bytesToHex(A_OKAY));
                return response;
            }

            // Lock sends challenge
            if (utils.startsWith(DOOR_CHALLENGE, commandApdu)) {
                Log.i(TAG, "Load Algorithms");
                Log.i(TAG, certificate.getSigAlgName());

                byte[] challenge = new byte[commandApdu.length - DOOR_CHALLENGE.length];

                System.arraycopy(commandApdu, 0, challenge, 0, challenge.length);

                byte[] sign = signChallenge(challenge);

                byte[] response = new byte[sign.length + A_OKAY.length];
                System.arraycopy(sign, 0, response, 0, sign.length);
                System.arraycopy(A_OKAY, 0, response, sign.length, A_OKAY.length);


                Context context = getApplicationContext();
                CharSequence text = "Door unlock sent!";
                int duration = Toast.LENGTH_SHORT;
                Toast toast = Toast.makeText(context, text, duration);
                toast.setGravity(Gravity.CENTER, 0, 0);
                toast.show();

                Log.i(TAG, "Got lock challenge. Our Response: " + utils.bytesToHex(response));
                return response;
            }

        } catch (Exception e) {
            Log.e(TAG, "processCommandApdu() | Exception: " + e.getMessage());
            return "Can I help you?".getBytes();
        }
        //
        // We're doing something outside our scope
        //
        Log.wtf(TAG, "processCommandApdu() | I don't know what's going on!!!.");
        return "Can I help you?".getBytes();
    }

    @Override
    public void onDeactivated(int reason) {
        Log.i(TAG, "onDeactivated() Fired! Reason: " + reason);
    }

}
