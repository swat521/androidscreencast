package net.srcz.android.screencast.api.injector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

import javax.swing.SwingUtilities;

import net.srcz.android.screencast.api.recording.QuickTimeOutputStream;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.RawImage;
import com.android.ddmlib.TimeoutException;

public class ScreenCaptureThread extends Thread {

	private BufferedImage image;
	private Dimension size;
	private IDevice device;
	private QuickTimeOutputStream qos = null;
	private boolean landscape = false;
	private ScreenCaptureListener listener = null;
	
	private CountDownLatch firstCapture = new CountDownLatch(1);
	
	private class MouseEvent {
		float x;
		float y;
		
		int type;
		long time;
		
		public MouseEvent(float x, float y, int type, long time) {		
			this.x = x;
			this.y = y;
			this.type = type;
			this.time = time;
		}		
		
	}
	
	private ArrayList<MouseEvent> lastEvents = new ArrayList<MouseEvent>();
	
	public void addMouseEvent(int type, float x, float y) {
		synchronized (lastEvents) {
			lastEvents.add(new MouseEvent(x, y, type, System.currentTimeMillis()));	
		}		
	}
	
	public ScreenCaptureListener getListener() {
		return listener;
	}

	public void setListener(ScreenCaptureListener listener) {
		this.listener = listener;
	}

	public interface ScreenCaptureListener {
		public void handleNewImage(Dimension size, BufferedImage image,
				boolean landscape);
	}

	public ScreenCaptureThread(IDevice device) {
		super("Screen capture");
		this.device = device;
		image = null;
		size = new Dimension();
	}

	public Dimension getPreferredSize() {
		return size;
	}

	public void run() {
		do {
			try {
				boolean ok = fetchImage();
				if(!ok)
					break;
				
				firstCapture.countDown();
				
			} catch (java.nio.channels.ClosedByInterruptException ciex) {
				break;
			} catch (IOException e) {
				System.err.println((new StringBuilder()).append(
						"Exception fetching image: ").append(e.toString())
						.toString());
			}

		} while (true);
	}

	public void startRecording(File f) {
		try {
			if(!f.getName().toLowerCase().endsWith(".mov"))
				f = new File(f.getAbsolutePath()+".mov");
			qos = new QuickTimeOutputStream(f,
					QuickTimeOutputStream.VideoFormat.JPG);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		qos.setVideoCompressionQuality(1f);
		qos.setTimeScale(30); // 30 fps
	}

	public void stopRecording() {
		try {
			QuickTimeOutputStream o = qos;
			qos = null;
			o.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private boolean fetchImage() throws IOException {
		if (device == null) {
			// device not ready
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				return false;
			}
			return true;
		}

		// System.out.println("Getting initial screenshot through ADB");
		RawImage rawImage = null;
		synchronized (device) {
			try {
				rawImage = device.getScreenshot();
			} catch (TimeoutException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (AdbCommandRejectedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (rawImage != null) {
			// System.out.println("screenshot through ADB ok");
			display(rawImage);
		} else {
			System.out.println("failed getting screenshot through ADB ok");
		}
		try {
			Thread.sleep(10);
		} catch (InterruptedException e) {
			return false;
		}

		return true;
	}

	public void toogleOrientation() {
		landscape = !landscape;
	}
	
	public void waitForFirstFrame() {
		try {
			firstCapture.await();
		} catch (InterruptedException e) {
			return;
		}
	}

	public void display(RawImage rawImage) {
		int width2 = landscape ? rawImage.height : rawImage.width;
		int height2 = landscape ? rawImage.width : rawImage.height;
		if (image == null) {
			image = new BufferedImage(width2, height2,
					BufferedImage.TYPE_INT_RGB);
			size.setSize(image.getWidth(), image.getHeight());
		} else {
			if (image.getHeight() != height2 || image.getWidth() != width2) {
				image = new BufferedImage(width2, height2,
						BufferedImage.TYPE_INT_RGB);
				size.setSize(image.getWidth(), image.getHeight());
			}
		}
		int index = 0;
		int indexInc = rawImage.bpp >> 3;
		for (int y = 0; y < rawImage.height; y++) {
			for (int x = 0; x < rawImage.width; x++, index += indexInc) {
				int value = rawImage.getARGB(index);
				if (landscape)
					image.setRGB(y, rawImage.width - x - 1, value);
				else
					image.setRGB(x, y, value);
			}
		}
		
		Graphics2D c = image.createGraphics();

		c.setColor(Color.RED);
		c.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		c.setStroke(new BasicStroke(20,BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		
		Path2D path = null;
		
		synchronized (lastEvents) {
					
			Iterator<MouseEvent> it = lastEvents.iterator();
			
			while ( it.hasNext()) {
				
				MouseEvent m = it.next();
			
				long deltaTime = System.currentTimeMillis() - m.time;
				
				if ( deltaTime > 1000) {
					it.remove();
					continue;
				}
				
				if ( m.type == ConstEvtMotion.ACTION_DOWN) {
					
					path = new Path2D.Float();				
					path.moveTo(m.x, m.y);
					
				} else if ( m.type == ConstEvtMotion.ACTION_UP) {
					
					if ( path == null) {
						path = new Path2D.Float();				
						path.moveTo(m.x, m.y);
					}
					
					path.lineTo(m.x, m.y);
					c.draw(path);
					path = null;
					
				} else if ( m.type == ConstEvtMotion.ACTION_MOVE) {
					
					if ( path == null) {
						path = new Path2D.Float();				
						path.moveTo(m.x, m.y);											
					} else {
						path.lineTo(m.x, m.y);
					}
					
				}					
			}

		}
		
		if ( path != null) {
			c.draw(path);
		}
		
		c.dispose();
		
		
		try {
			if (qos != null)
				qos.writeFrame(image, 10);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (listener != null) {
			SwingUtilities.invokeLater(new Runnable() {

				public void run() {
					listener.handleNewImage(size, image, landscape);
					// jp.handleNewImage(size, image, landscape);
				}
			});
		}
	}

}
