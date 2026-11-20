import com.solegram.messenger.SolegramLinkCleaner;
public class LinkTest {
  static int fail=0;
  static void t(String in, String exp){ String got=SolegramLinkCleaner.cleanText(in); if(!got.equals(exp)){fail++;System.out.println("FAIL\n in:  "+in+"\n got: "+got+"\n exp: "+exp);} }
  public static void main(String[] a){
    t("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf123","https://youtu.be/dQw4w9WgXcQ");
    t("https://www.youtube.com/watch?v=abc&t=42s&si=xyz","https://www.youtube.com/watch?v=abc&t=42s");
    t("https://open.spotify.com/track/123?si=ff00aa&context=x","https://open.spotify.com/track/123");
    t("see https://example.com/page?utm_source=tg&utm_medium=x&id=5#top.","see https://example.com/page?id=5#top.");
    t("https://www.instagram.com/reel/C1/?igsh=MWQ=","https://www.instagram.com/reel/C1/");
    t("https://site.ru/a?fbclid=IwAR0&b=2","https://site.ru/a?b=2");
    t("https://t.me/bot?start=utm_x","https://t.me/bot?start=utm_x");
    t("https://t.me/somebot?start=1&utm_source=a","https://t.me/somebot?start=1&utm_source=a");
    t("(https://a.com/x?gclid=1)","(https://a.com/x)");
    t("https://google.com/search?q=si&si=5","https://google.com/search?q=si&si=5");
    t("no links here","no links here");
    t("https://x.com/u/status/1?s=20&t=abc","https://x.com/u/status/1");
    t("www.example.com/?utm_campaign=z","www.example.com/");
    t("two https://a.com/?utm_a=1 and https://b.com/?k=v&fbclid=2!","two https://a.com/ and https://b.com/?k=v!");
    t("https://wikipedia.org/wiki/Foo_(bar)?utm_x=1","https://wikipedia.org/wiki/Foo_(bar)");
    System.out.println(fail==0?"ALL OK":"FAILED "+fail);
  }
}
