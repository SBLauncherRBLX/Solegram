import com.solegram.messenger.SolegramMetadataStripper;
import java.io.File;
public class StripTest { public static void main(String[] a){ for (String n: a){ File src=new File(n); File dst=new File(n.replace("/fx/","/out/")); dst.getParentFile().mkdirs(); System.out.println(n+" kind="+SolegramMetadataStripper.kindOf(src)+" ok="+SolegramMetadataStripper.strip(src,dst)); } } }
