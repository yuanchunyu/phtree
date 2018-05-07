/*
 * Copyright 2016-2018 Tilmann Zäschke. All Rights Reserved.
 *
 * This software is the proprietary information of Tilmann Zäschke.
 * Use is subject to license terms.
 */
package ch.ethz.globis.phtree.v16.bst;

import java.util.NoSuchElementException;

import ch.ethz.globis.phtree.v16.BSTHandler.BSTEntry;

/**
 * 
 * @author Tilmann Zaeschke
 *
 */
public class BSTIteratorMask {

	static class IteratorPos {
		BSTreePage page;
		short pos;

		void init(BSTreePage page, short pos) {
			this.page = page;
			this.pos = pos;
		}
	}

	static class IteratorPosStack {
		private final IteratorPos[] stack;
		private int size = 0;
		
		public IteratorPosStack(int depth) {
			stack = new IteratorPos[depth];
		}

		public boolean isEmpty() {
			return size == 0;
		}

		public IteratorPos prepareAndPush(BSTreePage page, short pos) {
			IteratorPos ni = stack[size++];
			if (ni == null)  {
				ni = new IteratorPos();
				stack[size-1] = ni;
			}
			ni.init(page, pos);
			return ni;
		}

		public IteratorPos peek() {
			return stack[size-1];
		}

		public IteratorPos pop() {
			return stack[--size];
		}

		public void clear() {
			size = 0;
		}
	}

	private BSTreePage currentPage = null;
	private short currentPos = 0;
	private long minMask;
	private long maxMask;
	private final IteratorPosStack stack = new IteratorPosStack(20);
	private long nextKey;
	private BSTEntry nextValue;
	private boolean hasValue = false;
    private final LLEntry tempEntry = new LLEntry(-1, null);
	
	public BSTIteratorMask() {
		//nothing
	}

	public BSTIteratorMask reset(BSTreePage root, long minMask, long maxMask) {
		this.minMask = minMask;
		this.maxMask = maxMask;
		this.currentPage = root;
		this.currentPos = 0;
		this.hasValue = false;
		this.stack.clear();
		findFirstPosInPage();
		return this;
	}

	public void adjustMinMax(long maskLower, long maskUpper) {
		this.minMask = maskLower;
		this.maxMask = maskUpper;
	}

	public boolean hasNextULL() {
		return hasValue;
	}

	
	private void goToNextPage() {
		if (stack.isEmpty()) {
			currentPage = null;
			return;
		}
		IteratorPos ip = stack.pop();
		currentPage = ip.page;
		currentPos = ip.pos;
		currentPos++;
		
		while (currentPos > currentPage.getNKeys()) {
			if (stack.isEmpty()) {
				close();
				return;// false;
			}
			ip = stack.pop();
			currentPage = ip.page;
			currentPos = ip.pos;
			currentPos++;
		}

		while (!currentPage.isLeaf()) {
			//we are not on the first page here, so we can assume that pos=0 is correct to 
			//start with

			//read last page
			stack.prepareAndPush(currentPage, currentPos);
			currentPage = currentPage.getPageByPos(currentPos);
			currentPos = 0;
		}
	}
	
	
	private boolean goToFirstPage() {
		while (!currentPage.isLeaf()) {
			//the following is only for the initial search.
			//The stored key[i] is the min-key of the according page[i+1}
			int pos2 = currentPage.binarySearch(currentPos, currentPage.getNKeys(), minMask);
	    	if (currentPage.getNKeys() == -1) {
				return false;
	    	}
			if (pos2 >=0) {
		        pos2++;
		    } else {
		        pos2 = -(pos2+1);
		    }
	    	currentPos = (short)pos2;

	    	BSTreePage newPage = currentPage.getPageByPos(currentPos);
			//are we on the correct branch?
	    	//We are searching with LONG_MIN value. If the key[] matches exactly, then the
	    	//selected page may not actually contain any valid elements.
	    	//In any case this will be sorted out in findFirstPosInPage()
	    	
	    	stack.prepareAndPush(currentPage, currentPos);
			currentPage = newPage;
			currentPos = 0;
		}
		return true;
	}
	
	private void gotoPosInPage() {
		//when we get here, we are on a valid page with a valid position 
		//(TODO check for pos after goToPage())
		//we only need to check the value.
		do {
			nextKey = currentPage.getKeys()[currentPos];
			nextValue = currentPage.getValues()[currentPos];
			hasValue = true;
			currentPos++;

			//now progress to next element

			//first progress to next page, if necessary.
			if (currentPos >= currentPage.getNKeys()) {
				goToNextPage();
				if (currentPage == null) {
					if (!check(nextKey)) {
						hasValue = false;
					}
					return;
				}
			}

			//check for invalid value
			if (currentPage.getKeys()[currentPos] > maxMask) {
				close();
				return;
			}
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO if check fails we could/should use inc() to find next valid page:
			//TODO 1) in leaf: compare to page.max()
			//TODO 2) in inner: (compare to max?, then:) compare to key of next-page (skip pages) 
		} while (!check(nextKey));
	}

	private boolean check(long key) {
		return ((key | minMask) & maxMask) == key;
	}


	private void findFirstPosInPage() {
		//find first page
		if (!goToFirstPage()) {
			close();
			return;
		}

		//find very first element. 
		currentPos = (short) currentPage.binarySearch(currentPos, currentPage.getNKeys(), minMask);
		if (currentPos < 0) {
			currentPos = (short) -(currentPos+1);
		}
		
		//check position
		if (currentPos >= currentPage.getNKeys()) {
			//maybe we walked down the wrong branch?
			goToNextPage();
			if (currentPage == null) {
				close();
				return;
			}
			//okay, try again.
			currentPos = (short) currentPage.binarySearch(currentPos, currentPage.getNKeys(), minMask);
			if (currentPos < 0) {
				currentPos = (short) -(currentPos+1);
			}
		}
		if (currentPos >= currentPage.getNKeys() 
				|| currentPage.getKeys()[currentPos] > maxMask) {
			close();
			return;
		}
		gotoPosInPage();
	}
	

	public LLEntry nextEntryReuse() {
		if (!hasNextULL()) {
			throw new NoSuchElementException();
		}

        tempEntry.set(nextKey, nextValue);
		if (currentPage == null) {
			hasValue = false;
		} else {
			gotoPosInPage();
		}
		return tempEntry;
	}


	public LLEntry nextEntry() {
		if (!hasNextULL()) {
			throw new NoSuchElementException();
		}

        LLEntry e = new LLEntry(nextKey, nextValue);
		if (currentPage == null) {
			hasValue = false;
		} else {
			gotoPosInPage();
		}
		return e;
	}
	
	public long nextKey() {
		if (!hasNextULL()) {
			throw new NoSuchElementException();
		}

        long ret = nextKey;
		if (currentPage == null) {
			hasValue = false;
		} else {
			gotoPosInPage();
		}
		return ret;
	}

	
	private void close() {
		currentPage = null;
	}

}