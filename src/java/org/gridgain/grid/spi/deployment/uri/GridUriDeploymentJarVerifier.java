// Copyright (C) GridGain Systems, Inc. Licensed under GPLv3, http://www.gnu.org/licenses/gpl.html

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.spi.deployment.uri;

import org.gridgain.grid.logger.*;
import org.gridgain.grid.typedef.internal.*;
import java.io.*;
import java.security.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.jar.*;

/**
 * Helper class that verifies either JAR file or JAR file input stream
 * if it is consistent or not. Consistency means that file was not changed
 * since build and all files mentioned in manifest are signed.
 *
 * @author 2005-2011 Copyright (C) GridGain Systems, Inc.
 * @version 3.1.1c.20062011
 */
final class GridUriDeploymentJarVerifier {
    /**
     * Enforces singleton.
     */
    private GridUriDeploymentJarVerifier() {
        // No-op.
    }

    /** Default buffer size = 4K. */
    private static final int BUF_SIZE = 4096;

    /**
     * Verify JAR-file that it was not changed since creation.
     * If parameter {@code allSigned} equals {@code true} and file is not
     * listed in manifest than method return {@code false}. If file listed
     * in manifest but doesn't exist in JAR-file than method return
     * {@code false}.
     *
     * @param jarName JAR file name.
     * @param allSigned If {@code true} then all files must be signed.
     * @param log Logger.
     * @return {@code true} if JAR file was not changed.
     * @throws IOException Thrown if JAR file or its entries could not be read.
     */
    static boolean verify(String jarName, boolean allSigned, GridLogger log) throws IOException {
        assert jarName != null;

        return verify0(jarName, null, allSigned, log);
    }

    /**
     * Verify JAR-file that all files declared in manifest are signed.
     * If manifest is {@code null} than method returns {@code true} if
     * public key is {@code null}.
     * If parameter {@code allSigned} equals {@code true} and file not
     * listed in manifest than method return {@code false}. If file
     * listed in manifest but doesn't exist in JAR-file than method
     * return {@code false}.
     *
     * @param jarName JAR file name.
     * @param pubKey Public key.
     * @param allSigned If {@code true} then all files must be signed.
     * @param log Logger.
     * @return {@code true} if JAR file is signed with given public key.
     * @throws IOException Thrown if JAR file or its entries could not be read.
     */
    static boolean verify(String jarName, PublicKey pubKey, boolean allSigned, GridLogger log)
        throws IOException {
        assert jarName != null;
        assert pubKey != null;

        return verify0(jarName, pubKey, allSigned, log);
    }

    /**
     * Tests whether given JAR file input stream was not changed since creation.
     *
     * @param in JAR file input stream.
     * @param allSigned Hint which means that all files of all entries must be
     *      signed.
     * @param log Logger.
     * @return {@code true} if JAR file input stream was not changed.
     * @throws IOException Thrown if JAR file stream or its entries could not
     *    be read.
     */
    static boolean verify(InputStream in, boolean allSigned, GridLogger log) throws IOException {
        assert in != null;

        return verify0(in, null, allSigned, log);
    }

    /**
     * Tests whether given JAR file input stream is signed with public key.
     * If manifest is {@code null} than method returns {@code true} if
     * public key is {@code null}.
     * If parameter {@code allSigned} equals {@code true} and file not
     * listed in manifest than method return {@code false}. If file
     * listed in manifest but doesn't exist in JAR-file than method
     * return {@code false}.
     *
     * @param in JAR file input stream.
     * @param pubKey Public key to be tested with.
     * @param allSigned Hint which means that all files in entry must be signed.
     * @param log Logger.
     * @return {@code true} if JAR file is signed with given public key.
     * @throws IOException Thrown if JAR file or its entries could not be read.
     */
    static boolean verify(InputStream in, PublicKey pubKey, boolean allSigned, GridLogger log)
        throws IOException {
        assert in != null;
        assert pubKey != null;

        return verify0(in, pubKey, allSigned, log);
    }

    /**
     * Tests whether all files in given JAR file input stream are signed
     * with public key. If manifest is {@code null} than method returns
     * {@code true} if public key is null.
     *
     * @param in JAR file input stream.
     * @param pubKey Public key to be tested with.
     * @param allSigned Hint which means that all files in entry must be signed.
     * @param log Logger.
     * @return {@code true} if JAR file is signed with given public key.
     * @throws IOException Thrown if JAR file or its entries could not be read.
     */
    private static boolean verify0(InputStream in, PublicKey pubKey, boolean allSigned, GridLogger log)
        throws IOException {
        assert in != null;

        JarInputStream jin = null;

        try {
            jin = new JarInputStream(in, true);

            Manifest manifest = jin.getManifest();

            // Manifest must be included in signed GAR file.
            if (manifest == null) {
                return pubKey == null;
            }

            Set<String> manifestFiles = getSignedFiles(manifest);

            JarEntry jarEntry;

            while((jarEntry = jin.getNextJarEntry()) != null) {
                if (jarEntry.isDirectory()) {
                    continue;
                }

                // Verify by reading the file if altered.
                // Will return quietly if no problem.
                verifyDigestsImplicitly(jin);

                if (verifyEntry(jarEntry, manifest, pubKey, allSigned, true) == false) {
                    return false;
                }

                manifestFiles.remove(jarEntry.getName());
            }

            return manifestFiles.size() <= 0;
        }
        catch (SecurityException e) {
            if (log.isDebugEnabled()) {
                log.debug("Got security error (ignoring): " + e.getMessage());
            }
        }
        finally {
            U.close(jin, log);
        }

        return false;
    }

    /**
     * Tests whether all files in given JAR file are signed
     * with public key. If manifest is {@code null} than method returns
     * {@code true} if public key is null.
     * <p>
     * <strong>DO NOT REFACTOR THIS METHOD. THERE IS A SUN DEFECT ABOUT PROCESSING JAR AS
     * FILE AND AS STREAM. THE PROCESSING IS DIFFERENT.</strong>
     *
     * @param jarName JAR file name.
     * @param pubKey Public key to be tested with.
     * @param allSigned Hint which means that all files in entry must be signed.
     * @param log Logger.
     * @return {@code true} if JAR file is signed with given public key.
     * @throws IOException Thrown if JAR file or its entries could not be read.
     */
    private static boolean verify0(String jarName, PublicKey pubKey, boolean allSigned, GridLogger log)
        throws IOException {
        JarFile jarFile = null;

        try {
            jarFile = new JarFile(jarName, true);

            Manifest manifest = jarFile.getManifest();

            // Manifest must be included in signed GAR file.
            if (manifest == null) {
                return pubKey == null;
            }

            Set<String> manifestFiles = getSignedFiles(manifest);

            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();

                if (jarEntry.isDirectory()) {
                    continue;
                }

                // Verify by reading the file if altered.
                // Will return quietly if no problem.
                verifyDigestsImplicitly(jarFile.getInputStream(jarEntry));

                if (verifyEntry(jarEntry, manifest, pubKey, allSigned, false) == false) {
                    return false;
                }

                manifestFiles.remove(jarEntry.getName());
            }

            return manifestFiles.size() <= 0;
        }
        catch (SecurityException e) {
            if (log.isDebugEnabled()) {
                log.debug("Got security error (ignoring): " + e.getMessage());
            }
        }
        finally {
            U.close(jarFile, log);
        }

        return false;
    }

    /**
     * Tests whether given JAR entry from manifest contains at least one
     * certificate with given public key.
     * <p>
     * Files which starts with "META-INF/" are always verified successfully.
     *
     * @param jarEntry Tested JAR entry.
     * @param manifest Manifest this entry belongs to.
     * @param pubKey Public key we are testing. If it is {@code null} returns
     *      {@code true}.
     * @param allSigned Hint which means that all files in entry must be signed.
     * @param makeCerts If {@code true} JAR entry certificates are scanned.
     *      Otherwise all JAR entry signers certificates  are scanned.
     * @return {@code true} if JAR entry is verified {@code false} otherwise.
     */
    private static boolean verifyEntry(JarEntry jarEntry, Manifest manifest, PublicKey pubKey, boolean allSigned,
        boolean makeCerts) {
        assert jarEntry != null;
        assert manifest != null;

        boolean inManifest = false;

        String entryName = jarEntry.getName();

        // Check that entry name contains in manifest file.
        if (manifest.getAttributes(entryName) != null || manifest.getAttributes("./" + entryName) != null ||
            manifest.getAttributes('/' + entryName) != null) {
            inManifest = true;
        }

        // Don't ignore files not listed in manifest and META-INF directory.
        if (allSigned == true && inManifest == false && entryName.toUpperCase().startsWith("META-INF/") == false) {
            return false;
        }

        // Looking at entries in manifest file.
        if (inManifest) {
            Certificate[] certs = makeCerts == false ? jarEntry.getCertificates() : getCertificates(jarEntry);

            boolean isSigned = certs != null && certs.length > 0;

            if (isSigned == false || pubKey != null && findKeyInCertificates(pubKey, certs) == false) {
                return false;
            }
        }

        return true;
    }

    /**
     * This checks that everything is valid and unchanged from the digest
     * listed in the manifest next to the name.
     *
     * @param in JAR file or JAR entry input stream.
     * @throws IOException Thrown if read fails.
     */
    private static void verifyDigestsImplicitly(InputStream in) throws IOException {
        byte[] buffer = new byte[BUF_SIZE];

        while (in.read(buffer, 0, buffer.length) != -1) {
            // Just read the entry. Will throw a SecurityException if signature
            // or digest check fails. Since we instantiated JarFile with parameter
            // true, that tells it to verify that the files match the digests
            // and haven't been changed.
        }
    }

    /**
     * Tests whether given certificate contains public key or not.
     *
     * @param key Public key which we are looking for.
     * @param certs Certificate which should be tested.
     * @return {@code true} if certificate contains given key and
     *      {@code false} if not.
     */
    private static boolean findKeyInCertificates(PublicKey key, Certificate[] certs) {
        if (key == null || certs == null) {
            return false;
        }

        for (Certificate cert : certs) {
            if (cert.getPublicKey().equals(key)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets all signed files from the manifest.
     * <p>
     * It scans all manifest entries and their attributes. If there is an attribute
     * name which ends with "-DIGEST" we are assuming that manifest entry name is a
     * signed file name.
     *
     * @param manifest JAR file manifest.
     * @return Either empty set if none found or set of signed file names.
     */
    private static Set<String> getSignedFiles(Manifest manifest) {
        Set<String> fileNames = new HashSet<String>();

        Map<String, Attributes> entries = manifest.getEntries();

        if (entries != null && entries.size() > 0) {
            for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
                Attributes attrs = entry.getValue();

                for (Map.Entry<Object, Object> attrEntry : attrs.entrySet()) {
                    if (attrEntry.getKey().toString().toUpperCase().endsWith("-DIGEST")) {
                        fileNames.add(entry.getKey());

                        break;
                    }
                }
            }
        }

        return fileNames;
    }

    /**
     * Gets all JAR file entry certificates.
     * Method scans entry for signers and than collects all their certificates.
     *
     * @param entry JAR file entry.
     * @return Array of certificates which corresponds to the entry.
     */
    private static Certificate[] getCertificates(JarEntry entry) {
        Certificate[] certs = null;

        CodeSigner[] signers = entry.getCodeSigners();

        // Extract the certificates in each code signer's cert chain.
        if (signers != null) {
            List<Certificate> certChains = new ArrayList<Certificate>();

            for (CodeSigner signer : signers) {
                certChains.addAll(signer.getSignerCertPath().getCertificates());
            }

            // Convert into a Certificate[]
            return certChains.toArray(new Certificate[certChains.size()]);
        }

        return certs;
    }
}
