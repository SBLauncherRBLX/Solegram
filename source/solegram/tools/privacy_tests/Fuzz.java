import com.solegram.messenger.SolegramMetadataStripper;
import java.io.*; import java.nio.file.*; import java.util.Random;
public class Fuzz { public static void main(String[] a) throws Exception { Random r=new Random(1); int runs=0;
 File tmp=File.createTempFile("fuzz",".bin"), out=File.createTempFile("fuzz",".out");
 for (String n: a){ byte[] d=Files.readAllBytes(Paths.get(n));
  for(int i=0;i<300;i++){ byte[] x=java.util.Arrays.copyOf(d, 1+r.nextInt(d.length)); if(i%2==0){ for(int k=0;k<3;k++) x[r.nextInt(x.length)]=(byte)r.nextInt(256);} Files.write(tmp.toPath(),x); long t=System.nanoTime(); SolegramMetadataStripper.strip(tmp,out); if(System.nanoTime()-t>1e9) System.out.println("SLOW "+n); runs++; } }
 System.out.println("fuzz runs "+runs+" no hang/crash"); } }
