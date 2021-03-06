/* Copyright (c) 2012 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.renderer;

import java.util.Random;

import org.apache.log4j.Logger;

import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;
import se.llbit.math.Ray.RayPool;
import se.llbit.util.VectorPool;

/**
 * Performs rendering work.
 * @author Jesper Öqvist <jesper@llbit.se>
 */
public class RenderWorker extends Thread {

	/**
	 * Sleep interval (in ns)
	 */
	private static final int SLEEP_INTERVAL = 75000000;

	private static final Logger logger =
			Logger.getLogger(RenderWorker.class);

	private final int id;
	private final AbstractRenderManager manager;

	private final WorkerState state;
	private long jobTime = 0;

	/**
	 * Create a new render worker, slave to a given render manager.
	 * @param manager
	 * @param id
	 * @param seed
	 */
	public RenderWorker(AbstractRenderManager manager, int id, long seed) {
		super("3D Render Worker " + id);

		this.manager = manager;
		this.id = id;
		state = new WorkerState();
		state.random = new Random(seed);
		state.vectorPool = new VectorPool();
		state.rayPool = new RayPool();
		state.ray = state.rayPool.get();
	}

	@Override
	public void run() {
		try {
			try {
				while (!isInterrupted()) {
					work(manager.getNextJob());
					manager.jobDone();
				}
			} catch (InterruptedException e) {
			}
		} catch (Throwable e) {
			logger.error("Render worker " + id +
					" crashed with uncaught exception.", e);
		}
	}

	/**
	 * Perform work
	 * @param jobId
	 * @throws InterruptedException interrupted while sleeping
	 */
	private final void work(int jobId) throws InterruptedException {

		Scene scene = manager.bufferedScene();
		
		Random random = state.random;
		Ray ray = state.ray;

		int width = scene.canvasWidth();
		int height = scene.canvasHeight();

		double halfWidth = width/(2.0*height);

		// calculate pixel bounds for this job
		int xjobs = (width+(manager.tileWidth-1))/manager.tileWidth;
		int x0 = manager.tileWidth * (jobId % xjobs);
		int x1 = Math.min(x0 + manager.tileWidth, width);
		int y0 = manager.tileWidth * (jobId / xjobs);
		int y1 = Math.min(y0 + manager.tileWidth, height);

		double[] samples = scene.getSampleBuffer();
		final Camera cam = scene.camera();

		long jobStart = System.nanoTime();

		if (scene.pathTrace()) {

			// this is intentionally incorrectly indented for readability
			for (int y = y0; y < y1; ++y) {
				int offset = y * width * 3 + x0 * 3;
				for (int x = x0; x < x1; ++x) {

					double sr = 0;
					double sg = 0;
					double sb = 0;

					for (int i = 0; i < RenderConstants.SPP_PASS; ++i) {
						double oy = random.nextDouble();
						double ox = random.nextDouble();

						cam.calcViewRay(ray, random, (-halfWidth + (x + ox)
								/ height), (-.5 + (y + oy) / height));

						scene.pathTrace(state);

						sr += ray.color.x;
						sg += ray.color.y;
						sb += ray.color.z;
					}
					double sinv = 1.0 / (scene.spp + RenderConstants.SPP_PASS);
					samples[offset+0] = (samples[offset+0] * scene.spp + sr) * sinv;
					samples[offset+1] = (samples[offset+1] * scene.spp + sg) * sinv;
					samples[offset+2] = (samples[offset+2] * scene.spp + sb) * sinv;

					if (scene.finalizeBuffer()) {
						scene.finalizePixel(x, y);
					}

					offset += 3;
				}
			}

		} else {

			Ray target = state.rayPool.get(ray);
			scene.trace(target);
			int tx = (int) QuickMath.floor(target.x.x + target.d.x * Ray.OFFSET);
			int ty = (int) QuickMath.floor(target.x.y + target.d.y * Ray.OFFSET);
			int tz = (int) QuickMath.floor(target.x.z + target.d.z * Ray.OFFSET);

			// this is intentionally incorrectly indented for readability
			for (int x = x0; x < x1; ++x)
			for (int y = y0; y < y1; ++y) {

			boolean firstFrame = scene.previewCount > 1;
			if (firstFrame) {
				if (((x+y)%2) == 0) {
					continue;
				}
			} else {
				if (((x+y)%2) != 0) {
					scene.finalizePixel(x, y);
					continue;
				}
			}

			cam.calcViewRay(ray, random,
					(-halfWidth + (double)x / height),
					(-.5 + (double)y / height));

			scene.quickTrace(state);

			// do target highlighting
			int rx = (int) QuickMath.floor(ray.x.x + ray.d.x * Ray.OFFSET);
			int ry = (int) QuickMath.floor(ray.x.y + ray.d.y * Ray.OFFSET);
			int rz = (int) QuickMath.floor(ray.x.z + ray.d.z * Ray.OFFSET);
			if (target.hit && tx == rx && ty == ry && tz == rz) {
				/*ray.color.x = ray.color.x * 0.5 + 0.5;
				ray.color.y = ray.color.y * 0.5;
				ray.color.z = ray.color.z * 0.5;
				ray.color.w = ray.color.w * 0.5 + 0.5;*/
				ray.color.x = 1 - ray.color.x;
				ray.color.y = 1 - ray.color.y;
				ray.color.z = 1 - ray.color.z;
				ray.color.w = 1;
			}

			samples[(y*width+x)*3+0] = ray.color.x;
			samples[(y*width+x)*3+1] = ray.color.y;
			samples[(y*width+x)*3+2] = ray.color.z;

			scene.finalizePixel(x, y);

			if (firstFrame) {
				if (y%2 == 0 && x < (width-1)) {
					// copy forward
					scene.copyPixel(y*width + x, 1);
				} else if (y%2 != 0 && x > 0) {
					// copy backward
					scene.copyPixel(y*width + x, -1);
				}
			}

			}

			state.rayPool.dispose(target);
		}
		jobTime += System.nanoTime() - jobStart;
		if (jobTime > SLEEP_INTERVAL) {
			if (manager.cpuLoad < 100) {
				// sleep = jobTime * (1-utilization) / utilization
				double load = (100.0 - manager.cpuLoad) / manager.cpuLoad;
				sleep((long) ((jobTime/1000000.0) * load));
			}
			jobTime = 0;
		}
	}

}
