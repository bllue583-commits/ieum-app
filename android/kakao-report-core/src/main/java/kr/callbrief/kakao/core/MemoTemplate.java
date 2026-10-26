package kr.callbrief.kakao.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** No raw transcript, user ID, bearer token or public per-report URL is accepted. */
public final class MemoTemplate {
    public final String text;
    public final String androidExecutionParams;
    private final String webUrl;
    public MemoTemplate(DailyReport report, String publicLandingUrl) {
        URI url=URI.create(publicLandingUrl);
        String host=url.getHost();
        if(!"https".equals(url.getScheme()) || host==null || !host.contains(".") || url.getUserInfo()!=null ||
                url.getQuery()!=null || url.getFragment()!=null || url.getPort()!=-1 ||
                host.toLowerCase(Locale.ROOT).endsWith(".invalid") || host.equals("localhost") ||
                host.matches("[0-9.]+") || host.endsWith(".local")) throw new IllegalArgumentException("registered_public_static_https_link_required");
        webUrl=url.toASCIIString();
        androidExecutionParams="screen=digest&date="+report.date;
        String body=report.shortSummary.replace("\r\n","\n").replace('\r','\n').trim();
        for(int i=0;i<body.length();i++) if(Character.isISOControl(body.charAt(i)) && body.charAt(i)!='\n' && body.charAt(i)!='\t')
            throw new IllegalArgumentException("invalid_summary_control");
        String title="이음 · "+report.date+" 일일 요약\n";
        String tail="\n전체 내용은 앱에서 확인하세요.";
        int budget=200-title.length()-tail.length();
        int end=Math.min(body.length(),body.length()>budget ? budget-1 : budget);
        if(end<body.length() && end>0 && Character.isHighSurrogate(body.charAt(end-1))) end--;
        text=title+body.substring(0,end)+(end<body.length()?"…":"")+tail;
        // Make room for the ellipsis within the strict UTF-16 limit (also <=200 Unicode scalars).
        if(text.length()>200) throw new IllegalArgumentException("summary_limit");
    }
    public String json() {
        Map<String,Object> link=new LinkedHashMap<>();
        link.put("web_url",webUrl); link.put("mobile_web_url",webUrl); link.put("android_execution_params",androidExecutionParams);
        Map<String,Object> root=new LinkedHashMap<>();
        root.put("object_type","text"); root.put("text",text); root.put("link",link); root.put("button_title","앱에서 자세히 보기");
        return KakaoJson.encode(root);
    }
    @Override public String toString() { return "MemoTemplate[content withheld]"; }
}
