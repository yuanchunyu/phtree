/*
 * Copyright 2011-2016 ETH Zurich. All Rights Reserved.
 * Copyright 2016-2018 Tilmann Zäschke. All Rights Reserved.
 *
 * This software is the proprietary information of ETH Zurich
 * and Tilmann Zäschke.
 * Use is subject to license terms.
 */
package ch.ethz.globis.phtree.v16;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.PriorityQueue;

import ch.ethz.globis.phtree.PhDistance;
import ch.ethz.globis.phtree.PhEntryDist;
import ch.ethz.globis.phtree.PhFilterDistance;
import ch.ethz.globis.phtree.PhTree.PhKnnQuery;
import ch.ethz.globis.phtree.PhTreeHelper;
import ch.ethz.globis.phtree.v16.Node.BSTEntry;
import ch.ethz.globis.phtree.v16.bst.BSTIteratorAll;

/**
 * kNN query implementation that uses preprocessors and distance functions.
 * 
 * Implementation after Hjaltason and Samet (with some deviations: no MinDist or MaxDist used).
 * G. R. Hjaltason and H. Samet., "Distance browsing in spatial databases.", ACM TODS 24(2):265--318. 1999
 *
 * Additional modification by using HC-Address for estimating distance, actual distance is 
 * calculated only when required. 
 * 
 * @param <T> value type
 */
public class PhQueryKnnHSZ<T> implements PhKnnQuery<T> {

	private static final PhDEComp COMP = new PhDEComp();

	private final ZComp zComp = new ZComp();
	private final int dims;
	private PhTree16<T> pht;
	private PhDistance distance;
	private long[] center;
	private final PhFilterDistance checker;
	private final ArrayList<PhEntryDist<T>> results = new ArrayList<>(); 
	private final ArrayList<PhEntryDist<Object>> pool = new ArrayList<>(); 
	private final PriorityQueue<PhEntryDist<Object>> queueEst = new PriorityQueue<>(COMP);
	private final PriorityQueue<PhEntryDist<Object>> queueLx = new PriorityQueue<>(COMP);
	private final BSTIteratorAll iterNode = new BSTIteratorAll();
	private Iterator<PhEntryDist<T>> iterResult;


	/**
	 * Create a new kNN/NNS search instance.
	 * @param pht the parent tree
	 */
	public PhQueryKnnHSZ(PhTree16<T> pht) {
		this.dims = pht.getDim();
		this.pht = pht;
		this.checker = new PhFilterDistance();
	}

	@Override
	public long[] nextKey() {
		return nextEntryReuse().getKey();
	}

	@Override
	public T nextValue() {
		return nextEntryReuse().getValue();
	}

	@Override
	public PhEntryDist<T> nextEntry() {
		return iterResult.next();
	} 

	@Override
	public PhEntryDist<T> nextEntryReuse() {
		//TODO
		return iterResult.next();
	}

	@Override
	public boolean hasNext() {
		return iterResult.hasNext();
	}

	@Override
	public T next() {
		return nextValue();
	}

	@Override
	public PhKnnQuery<T> reset(int nMin, PhDistance dist, long... center) {
		this.distance = dist == null ? this.distance : dist;
		this.center = center;
		
		//TODO pool entries??/
		this.queueEst.clear();
		this.queueLx.clear();
		this.results.clear();
		
		
		if (nMin <= 0 || pht.size() == 0) {
			iterResult = Collections.<PhEntryDist<T>>emptyList().iterator();
			return this;
		}
		
		//Initialize queue
		PhEntryDist<Object> rootE = new PhEntryDist<>(new long[dims], pht.getRoot(), 0);
		this.queueLx.add(rootE);
		
		search(nMin);
		iterResult = results.iterator();
		
		return this;
	}

		
	/**
	 * Ensure a valid candidate at top of LxQueue.
	 * @param k
	 */
	private void validateLxQueue(int k) {
		//Check with estimated distance. Ensure that there is no candidate in queueEst that may be
		//closer that the first candidate in queueLx.
		while (!queueEst.isEmpty() && (queueLx.isEmpty() || queueEst.peek().dist() <= queueLx.peek().dist())) {
			//move to queueLx
			PhEntryDist<Object> entry = queueEst.poll();
			assignDistance(entry);
			queueLx.add( entry ); 
		}
	}
	
	private void search(int k) {
		while (!queueLx.isEmpty() || !queueEst.isEmpty()) {

			//ensure that 1st LX entry is valid
			validateLxQueue(k);

			//process 1st entry
			PhEntryDist<Object> candidate = queueLx.poll();
			
			Object val = candidate.getValue();
			if (!(val instanceof Node)) {
				//data entry
//TODO				if (checker == null || checker.isValid(candidate.getKey())) {
				results.add((PhEntryDist<T>) candidate);
				if (results.size() >= k) {
					return;
				}
			} else {
				//inner node
				Node node = (Node)val;
				iterNode.reset(node.getRoot());
				if (node.getEntryCount() > 4) {
					//Use estimated distances

					//Calculate how many permutations are at most possible -> distances
					double[] distances = new double[dims];
					distance.knnCalcDistances(center, candidate.getKey(), node.getPostLen() + 1, distances);

					long relativeQuadrantOfCenter;
					if (candidate.dist() <= 0) {
						relativeQuadrantOfCenter = PhTreeHelper.posInArray(center, node.getPostLen());
					} else {
						relativeQuadrantOfCenter = calcRelativeQuadrants(node, candidate.getKey());
					}

					while (iterNode.hasNextEntry()) {
						BSTEntry e2 = iterNode.nextEntry();
						double d = estimateDist(e2, relativeQuadrantOfCenter, distances);
//						double dLx = calcDistance(createEntry(e2.getKdKey(), e2.getValue(), -1));
						//TODO return dLx-1 if perm==0 and d==0...!?!?!?!   candidate.dts() == 0 ???
//						if (d <= 0 && dLx == -1) {
//							//Quadrant that contains the 'center'
//							PhEntryDist<Object> match = createEntry(e2.getKdKey(), e2.getValue(), 0);
//							queueLx.add(match);
//							continue;
//						} else {
//							if (dLx < d) {
//								//TODO
//								System.out.println("distances: " + Arrays.toString(distances));
//								throw new IllegalStateException("d-dLX: " + d + " / " + dLx);
//							}
							queueEst.add( createEntry(e2.getKdKey(), e2.getValue(), d) );
//						}
					}
				} else {
					//Add directly to main queue
					while (iterNode.hasNextEntry()) {
						BSTEntry e2 = iterNode.nextEntry();
						PhEntryDist<Object> subE = createEntry(e2.getKdKey(), e2.getValue(), -1);
						assignDistance(subE);
						queueLx.add( subE ); 
					}
				}
//TODO				pool.add(candidate);
			}				
		}
	}
	
	
//	private void assignDistance(PhEntryDist<Object> e) {
//		BSTEntry e2 = (BSTEntry) e.getValue();
//		double d;
//		if (e2.getValue() instanceof Node) {
//			Node sub = (Node) e2.getValue();
//			d = distToNode(e2.getKdKey(), sub.getPostLen() + 1);
//		} else {
//			d = distance.dist(center, e2.getKdKey());
//		}
//		e.setDist(d);
//	}
	
	private void assignDistance(PhEntryDist<Object> e) {
		double d;
		if (e.getValue() instanceof Node) {
			Node sub = (Node) e.getValue();
			d = distToNode(e.getKey(), sub.getPostLen() + 1);
		} else {
			d = distance.dist(center, e.getKey());
		}
		e.setDist(d);
	}
	
	private double calcDistance(PhEntryDist<Object> e) {
		double d;
		if (e.getValue() instanceof Node) {
			Node sub = (Node) e.getValue();
			d = distToNode(e.getKey(), sub.getPostLen() + 1);
		} else {
			d = distance.dist(center, e.getKey());
		}
		return d;
	}
	
	private static final double EPS = 0.999999999;
	
	private double estimateDist(BSTEntry e2, long centerQuadrant, double[] distances) {
		int permCount = Long.bitCount(centerQuadrant ^ e2.getKey());
		return permCount == 0 ? 0 : distances[permCount-1]*EPS;
		
//		return distance.knnCalcMaximumPermutationCount(distances, maxDist)
		
//		double dEst;
//		if (e2.getValue() instanceof Node) {
//			Node sub = (Node) e2.getValue();
//			dEst = distToNode(e2.getKdKey(), sub.getPostLen() + 1);
//		} else {
//			dEst = distance.dist(center, e2.getKdKey());
//		}
//		return dEst;
	}
	
	private long calcRelativeQuadrants(Node node, long[] prefix) {
		//calc 'divePos', ie. whether prefix is below/above 'center' for each dimension
		long[] kNNCenter = center;
    	
        long relativeKNNpos = 0;
        //We want to get the center, so no +1 for postLen
        long prefixMask = (-1L) << node.getPostLen()+1;
        long prefixBit = 1L << node.getPostLen();
        for (int i = 0; i < prefix.length; i++) {
        	relativeKNNpos <<= 1;
        	//set pos-bit if bit is set in value
        	long nodeCenter = (prefix[i] & prefixMask) | prefixBit;
        	relativeKNNpos |= (kNNCenter[i] >= nodeCenter) ? 1 : 0;
        }
        return relativeKNNpos;
	}
	
	
	private PhEntryDist<Object> createEntry(long[] key, Object val, double dist) {
		if (pool.isEmpty()) {
			return new PhEntryDist<Object>(key, val, dist);
		}
		PhEntryDist<Object> e = pool.remove(pool.size() - 1);
		e.setKeyInternal(key);
		e.set(val, dist);
		return e;
	}


	private double distToNode(long[] prefix, int bitsToIgnore) {
		long maskMin = (-1L) << bitsToIgnore;
		long maskMax = ~maskMin;
		long[] buf = new long[prefix.length];
		for (int i = 0; i < buf.length; i++) {
			//if v is outside the node, return distance to closest edge,
			//otherwise return v itself (assume possible distance=0)
			long min = prefix[i] & maskMin;
			long max = prefix[i] | maskMax;
			buf[i] = min > center[i] ? min : (max < center[i] ? max : center[i]); 
		}

		return distance.dist(center, buf);
	}

	
	private static class PhDEComp implements Comparator<PhEntryDist<?>> {
	    @Override
		public int compare(PhEntryDist<?> a, PhEntryDist<?> b) {
	    	double d1 = a.dist();
	    	double d2 = b.dist();
            return d1 < d2 ? -1 : d1 > d2 ? 1 : 0;
	    }
	}


	private static class ZComp implements Comparator<BSTEntry> {
		long key;
	    @Override
		public int compare(BSTEntry a, BSTEntry b) {
	    	int h1 = Long.bitCount(a.getKey() ^ key);
	    	int h2 = Long.bitCount(b.getKey() ^ key);
            return h1 - h2;
	    }
	}

}