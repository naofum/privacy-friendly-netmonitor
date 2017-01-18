package org.secuso.privacyfriendlynetmonitor.ConnectionAnalysis;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import org.secuso.privacyfriendlynetmonitor.Assistant.AsyncCertVal;
import org.secuso.privacyfriendlynetmonitor.Assistant.AsyncDNS;
import org.secuso.privacyfriendlynetmonitor.Assistant.Const;
import org.secuso.privacyfriendlynetmonitor.Assistant.KnownPorts;
import org.secuso.privacyfriendlynetmonitor.Assistant.RunStore;
import org.secuso.privacyfriendlynetmonitor.Assistant.ToolBox;
import org.secuso.privacyfriendlynetmonitor.BuildConfig;
import org.secuso.privacyfriendlynetmonitor.R;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.bjoernr.ssllabs.ConsoleUtilities;

/**
 * Collector class collects data from the services and processes it for inter process communication
 * with the UI.
 */
public class Collector {

    //application caches
    private static HashMap<Integer, PackageInfo> sCachePackage = new HashMap<>();
    private static HashMap<Integer, Drawable> sCacheIcon = new HashMap<>();
    private static HashMap<Integer, String> sCacheLabel = new HashMap<>();
    private static HashMap<String, String> sCacheDNS = new HashMap<>();

    //ReportDetail information
    public static Boolean isCertVal;
    public static HashMap<String, Map<String, Object>> mCertValMap = new HashMap<>();
    public static List<String> sCertValList = new ArrayList<>();
    public static ArrayList<String[]> sDetailReportList = new ArrayList<>();
    public static Report sDetailReport;

    //Data processing maps
    private static ArrayList<Report> sReportList = new ArrayList<>();
    private static HashMap<Integer, List<Report>> mUidReportMap = new HashMap<>();
    private static HashMap<Integer, List<Report>> mFilteredUidReportMap = new HashMap<>();

    //Pushed the newest available information as deep copy.
    public static HashMap<Integer, List<Report>> provideSimpleReports(){
        updateReports();
        mFilteredUidReportMap = filterReports();
        return mFilteredUidReportMap;
    }

    //Generate an overview List, with only one report per remote address per app
    private static HashMap<Integer, List<Report>> filterReports() {
        HashMap<Integer, List<Report>> filteredReportsByApp = new HashMap<>();
        HashSet<String> filterMap = new HashSet<>();
        String address;
        ArrayList<Report> list;
        ArrayList<Report> filteredList;
        for (int key : mUidReportMap.keySet()) {
            filteredReportsByApp.put(key, new ArrayList<Report>());
            list = (ArrayList<Report>) mUidReportMap.get(key);
            filteredList = (ArrayList<Report>) filteredReportsByApp.get(key);
            filterMap.clear();
            for (int i = 0; i < list.size(); i++) {
                address = list.get(i).remoteAdd.getHostAddress();
                if (!filterMap.contains(address)) {
                    filteredList.add(list.get(i));
                    filterMap.add(address);
                }
            }
        }
        return filteredReportsByApp;
    }

    private static void updateReports(){
        //update reports
        pull();
        //process reports (passive mode)
        fillPackageInformation();
        //resolve remote hosts (in cache or permission.INTERNET required)
        new AsyncDNS().execute("");
        //sorting
        sortReportsToMap();
        //Generate ssl analyze requests
        if(isCertVal){ fillCertRequests(); }

    }

    //Search for resolved hostnames and add them to the resolved list
    private static void fillCertRequests() {
        Set<Integer> keySet = mFilteredUidReportMap.keySet();
        ArrayList<Report> list;
        Report r;
        String ip;
        for ( int i : keySet) {
            list = (ArrayList<Report>)mFilteredUidReportMap.get(i);
            for (int j = 0; j < list.size(); j++) {
                r = list.get(j);
                //Add to certificate validation, if port 443 (TLS), resolved hostname and not yet
                //analyzed
                ip = r.remoteAdd.getHostAddress();
                    if (KnownPorts.isTlsPort(r.remotePort) && hasHostName(ip) &&
                            !mCertValMap.containsKey(getDnsHostName(ip)) &&
                            !sCertValList.contains(getDnsHostName(ip))) {
                        sCertValList.add(getDnsHostName(ip));
                    }
            }
        }
    }

    //Sorts the reports by app package name to a HashMap
    private static void sortReportsToMap() {
        mUidReportMap = new HashMap<>();
        for (int i = 0; i < sReportList.size(); i++) {
            Report r = sReportList.get(i);
            if (!mUidReportMap.containsKey(r.uid)) {
                mUidReportMap.put(r.uid, new ArrayList<Report>());
            }
            mUidReportMap.get(r.uid).add(r);
        }
    }


    //pull records from detector and make a deep copy for frontend - usage
    private static void pull() {
        ArrayList<Report> reportList = new ArrayList<>();
        Set<Integer> keySet = Detector.sReportMap.keySet();
        for (int i : keySet) {
            reportList.add(Detector.sReportMap.get(i));
        }
        sReportList = deepCopyReportList(reportList);
    }

    //Make an async reverse DNS request
    public static void resolveHosts() {
        for (int i = 0; i < sReportList.size(); i++){
            Report r = sReportList.get(i);
            if (!hasHostName(r.remoteAdd.getHostAddress())) {
                try {
                    String hostName = r.remoteAdd.getHostName();
                    sCacheDNS.put(r.remoteAdd.getHostAddress(), hostName);
                    if (Const.IS_DEBUG){
                        Log.d("ReverseDNS", "Reverse DNS for " + r.remoteAdd.getHostAddress()
                                + hostName);
                    }
                } catch (RuntimeException e) {
                    if (Const.IS_DEBUG) {
                        Log.e(Const.LOG_TAG, "Attempt to resolve host name failed");
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    //Make an async request to get host information from sslLabs
    static void updateCertVal() {
        if (sCertValList.size() > 0){
            new AsyncCertVal().execute();
        }
    }

    private static void fillPackageInformation() {
        for (int i = 0; i < sReportList.size(); i++) {
            Report r = sReportList.get(i);
            if(!sCachePackage.containsKey(r.uid)) {
                updatePackageCache();
            }
            if(sCachePackage.containsKey(r.uid)){
                PackageInfo pi = sCachePackage.get(r.uid);
                r.appName = pi.applicationInfo.name;
                r.packageName = pi.packageName;
            } else {
                r.appName = "Unknown App";
                r.appName = "app.unknown";
            }
        }
    }

    //Make a deep copy of the report list
    private static ArrayList<Report> deepCopyReportList(ArrayList<Report> reportList) {
        ArrayList<Report> cloneList = new ArrayList<>();
        try {
            for (int i = 0; i < reportList.size(); i++) {
                ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
                ObjectOutputStream out = new ObjectOutputStream(byteOut);
                out.writeObject(reportList.get(i));
                out.flush();
                ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(byteOut.toByteArray()));
                cloneList.add(Report.class.cast(in.readObject()));
            }
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return cloneList;
    }

    //Updates the PkgInfo hash map with new entries.
    private static void updatePackageCache() {
        sCachePackage = new HashMap<>();
        if(Const.IS_DEBUG){ printAllPackages(); }
        ArrayList<PackageInfo> infoList = (ArrayList<PackageInfo>) getPackages(RunStore.getContext());
        for (PackageInfo i : infoList) {
            if (i != null) {
                sCachePackage.put(i.applicationInfo.uid, i);
            }
        }
        addSysPackage();
    }

    //Generate a system user dummy for UID 0
    private static void addSysPackage() {
        // Add root
        PackageInfo root = new PackageInfo();
        root.packageName = "com.android.system";
        root.versionCode = BuildConfig.VERSION_CODE;
        root.versionName = BuildConfig.VERSION_NAME;
        root.applicationInfo = new ApplicationInfo();
        root.applicationInfo.name = "System";
        root.applicationInfo.uid = 0;
        root.applicationInfo.icon = 0;
        sCachePackage.put(root.applicationInfo.uid, root);
    }

    //Get a list with all currently installed packages
    private static List<PackageInfo> getPackages(Context context) {
        synchronized (context.getApplicationContext()) {
                PackageManager pm = context.getPackageManager();
            return new ArrayList<>(pm.getInstalledPackages(0));
        }
    }

    //degub print: Print all reachable active processes
    private static void printAllPackages() {
            ArrayList<PackageInfo> infoList = (ArrayList<PackageInfo>) getPackages(RunStore.getContext());
            for (PackageInfo i : infoList) {
                Log.d(Const.LOG_TAG, i.packageName + " uid_" + i.applicationInfo.uid);
            }
    }

    public static Drawable getIcon(int uid){
        if(!sCacheIcon.containsKey(uid)){
            if(sCachePackage.containsKey(uid)){
                sCacheIcon.put(uid, sCachePackage.get(uid).applicationInfo.
                        loadIcon(RunStore.getContext().getPackageManager()));
            } else {
                return RunStore.getContext().getDrawable(android.R.drawable.sym_def_app_icon);
            }
        }
        return sCacheIcon.get(uid);
    }

    public static String getLabel(int uid){
        if(!sCacheLabel.containsKey(uid)){
            if(sCachePackage.containsKey(uid)){
                sCacheLabel.put(uid, (String) sCachePackage.get(uid).applicationInfo.
                        loadLabel(RunStore.getContext().getPackageManager()));
            }
            else {
                return RunStore.getContext().getString(R.string.unknown_app);
            }
        }
        return sCacheLabel.get(uid);
    }

    public static String getPackage(int uid) {
        if(sCachePackage.containsKey(uid)) {
            return sCachePackage.get(uid).packageName;
        } else{
            return RunStore.getContext().getString(R.string.unknown_package);
        }
    }


    public static String getDnsHostName(String hostAdd) {
        if (sCacheDNS.containsKey(hostAdd)){
            return sCacheDNS.get(hostAdd);
        } else { return hostAdd; }
    }

    public static Boolean hasHostName(String hostAdd) {
        return sCacheDNS.containsKey(hostAdd);
    }


    public static String getCertHost(String hostname) {
        if(mCertValMap.containsKey(hostname)) {
            Map<String, Object> map = mCertValMap.get(hostname);
            Log.d(Const.LOG_TAG, ConsoleUtilities.mapToConsoleOutput(map));
            if (analyseReady(map)) {
                if(map.containsKey("host")){
                    return (String)map.get("host");
                } else {
                    return hostname;
                }
            }
        }
        return hostname;
    }

    public static String getMetric(String hostname) {
        String grade;

        if(mCertValMap.containsKey(hostname)){
            Map<String, Object> map = mCertValMap.get(hostname);
            Log.d(Const.LOG_TAG, ConsoleUtilities.mapToConsoleOutput(map));
            if (analyseReady(map)){
                grade = readEndpoints(map);
                if (grade.equals("no_grade")){
                    return "no_grade";
                } else if (grade.equals("Certificate not valid for domain name")){
                    handleInvalidDomainName(map);
                    return "RESOLVING CERTIFICATE HOSTS";
                } else if (grade.equals("no_endpoints")){
                return "no_endpoints";
                } else {
                    return grade;
                }
            } else { return "PENDING"; }
        } else { return "PENDING"; }
    }

    private static String readEndpoints(Map<String, Object> map) {
        final String result;
        if(map.containsKey("endpoints")){
            ArrayList endpointsList = (ArrayList) map.get("endpoints");
            HashMap endpoints = (HashMap) endpointsList.get(0);
            if(endpoints.containsKey("grade")){
                result = (String)endpoints.get("grade");
            } else if (endpoints.containsKey("statusMessage")){
                result = (String)endpoints.get("statusMessage");
            } else {
                result = "no_status";
            }
        } else {
            result = "no_endpoints";
        }
        return result;
    }

    //Handle "Certificate not valid for domain name" Error (e.g. google services)
    private static void handleInvalidDomainName(Map<String, Object> map) {
        if(map.containsKey("certHostnames") && map.containsKey("host")){
            ArrayList certNames = (ArrayList)map.get("certHostnames");
            String oldHost = (String)map.get("host");
            String certHost = (String)certNames.get(0);
            certHost = certHost.replace("*.", "");
            if (mCertValMap.containsKey(certHost) && mCertValMap.containsKey(oldHost)) {
                mCertValMap.put(oldHost, mCertValMap.get(certHost));
                if(sCertValList.contains(oldHost)) { sCertValList.remove(oldHost); }
            } else {
                if (!sCertValList.contains(certHost)){
                    sCertValList.add(certHost);
                }
            }
        }
    }

    public static void updateCertHostHandler() {
        Set<String> keySet = Collector.mCertValMap.keySet();
        Map map;
        for (String key:keySet ) {
            map = (HashMap)Collector.mCertValMap.get(key);
            if(map.containsKey("host")){
                String certHost = (String)map.get("host");
                if(!key.equals(map.get(key)) && !Collector.analyseReady(map)){
                    Collector.mCertValMap.put(key, Collector.mCertValMap.get(certHost));
                }
            }
        }
    }

    //Checks if ssl analysis has been completed
    public static boolean analyseReady(Map<String, Object> map) {
        String status = (String) map.get("status");
        return status != null && status.equals("READY");
    }

    public static void provideDetail(int uid, byte[] remoteAddHex) {
        ArrayList<Report> filterList = filterReportsByAdd(uid, remoteAddHex);
        sDetailReport = filterList.get(0);
        buildDetailStrings(filterList);
    }

    private static ArrayList<Report> filterReportsByAdd(int uid, byte[] remoteAddHex){
        List<Report> reportList = mUidReportMap.get(uid);
        ArrayList<Report> filterList = new ArrayList<>();
        for (int i = 0; i < reportList.size(); i++){
            if (Arrays.equals(reportList.get(i).remoteAddHex, remoteAddHex)){
                filterList.add(reportList.get(i));
            }
        }
        return filterList;
    }

    private static void buildDetailStrings(ArrayList<Report> filterList) {
        ArrayList<String[]> l = new ArrayList<>();
        Report r = filterList.get(0);
        PackageInfo info = sCachePackage.get(r.uid);

        l.add(new String[]{"User ID", "" + r.uid});
        l.add(new String[]{"App Version", "" + info.versionName});
        l.add(new String[]{"Installed On", "" + new Date(info.firstInstallTime).toString()});
        l.add(new String[]{"App Version", "" + info.applicationInfo.loadDescription(RunStore.getContext().getPackageManager())});
        l.add(new String[]{"", ""});
        l.add(new String[]{"Remote Address", r.remoteAdd.getHostAddress()});
        l.add(new String[]{"Remote Address(HEX)", ToolBox.printHexBinary(r.remoteAdd.getAddress())});
        if(hasHostName(r.remoteAdd.getHostAddress())){
            l.add(new String[]{"Remote Host", getDnsHostName(r.remoteAdd.getHostAddress())});
        }else {
            l.add(new String[]{"Remote Host", "name not resolved"});
        }
        l.add(new String[]{"Local Address", r.localAdd.getHostAddress()});
        l.add(new String[]{"Local Address(HEX)", ToolBox.printHexBinary(r.localAdd.getAddress())});
        l.add(new String[]{"", ""});
        l.add(new String[]{"Service Port", "" + r.remotePort});
        l.add(new String[]{"Payload Protocol", "" + KnownPorts.resolvePort(r.remotePort)});
        l.add(new String[]{"Transport Protocol", "" + r.type});
        l.add(new String[]{"Last Seen", r.timestamp.toString()});
        l.add(new String[]{"", ""});
        l.add(new String[]{"Simultaneous Connections", "" + filterList.size()});
        for (int i = 0; i < filterList.size(); i++){
            Report r2 = filterList.get(i);
            l.add(new String[]{"(" + (i + 1) + ")src port > dst port",
                    r2.localPort + " > " + r2.remotePort});
            l.add(new String[]{"    socket-state ", getTransportState(r.state)});
        }
        l.add(new String[]{"", ""});

        sDetailReportList = l;
    }


    private static String getTransportState(byte[] state) {
        String status;
        String stateHex = ToolBox.printHexBinary(state);
        switch (stateHex) {
            case "01":
                status = "ESTABLISHED";
                break;
            case "02":
                status = "SYN_SENT";
                break;
            case "03":
                status = "SYN_RECV";
                break;
            case "04":
                status = "FIN_WAIT1";
                break;
            case "05":
                status = "FIN_WAIT2";
                break;
            case "06":
                status = "TIME_WAIT";
                break;
            case "07":
                status = "CLOSE";
                break;
            case "08":
                status = "CLOSE_WAIT";
                break;
            case "09":
                status = "LAST_ACK";
                break;
            case "0A":
                status = "LISTEN";
                break;
            case "0B":
                status = "CLOSING";
                break;
            case "0C":
                status = "NEW_SYN_RECV";
                break;
            default:
                status = "UNKNOWN";
                break;
        }
        return status;
    }



}