package org.apache.hadoop.streaming;

import java.io.IOException;  
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.apache.hadoop.fs.FileSystem;  
import org.apache.hadoop.fs.Path;  
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.JobConf;  
import org.apache.hadoop.mapred.Reporter;  
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.FileInputFormat;

import com.google.common.base.Stopwatch;

/*
 * 自定义的输入格式ContentInputFormat，在函数getSplits中，类GetVideoInfo和JNI技术，获取自定义的输入分片。
 * 并在函数getRecordReader()中调用自定义的类ContentRecordReader，来实现按每个GOP读取key, value, 并送到map中处理。
 */

public class ContentInputFormat extends FileInputFormat<NullWritable, BytesWritable> {   
	private List<InputSplit> splits = null;
	private FileStatus[] files = null;
	private String inputFileName;	
	private long[] offsetAndSize = null; 
	private byte[] buffer = null;
	private final int PROCESS_COMPLETE = 0;
	private final int PROCESS_NO_ERROR = 1;
	private static int PROCESS_ERROR = 1001;
	private long splitSize = 0;
	private ArrayList<Long> splitInfo = null;
	private HashMap<ArrayList<Long>, HashMap<ArrayList<Long>, byte[]>> splitAndGOPInfo = null;
	
	/*
	 * (non-Javadoc)
	 * @see org.apache.hadoop.mapred.FileInputFormat#isSplitable(org.apache.hadoop.fs.FileSystem, org.apache.hadoop.fs.Path)
	 * 确定分片可分割。
	 */
    protected boolean isSplitable(FileSystem fs, Path filename) {    
        return true;   
    }    
    
   public InputSplit[] getSplits(JobConf job, int numSplits) throws IOException {
	Stopwatch sw = new Stopwatch().start();
	files = listStatus(job);
	splits = new ArrayList<InputSplit>();
	System.out.println("*** the numbers of splits ***" + numSplits);
	for (FileStatus file: files) {
		splitAndGOPInfo = new HashMap<ArrayList<Long>, HashMap<ArrayList<Long>, byte[]>>();
		Path path = file.getPath();
		FSDataInputStream in = null; 
		FileSystem fs = path.getFileSystem(job);
		in = fs.open(path);
		inputFileName = file.getPath().getName();
		System.out.println("*** input file: " + path.toString());
        System.out.println("*** input file length: " + file.getLen());
		System.out.println("*** the mp4 file to process : " + inputFileName);
		long length = file.getLen();
		if (length != 0) {	
			GetVideoInfo.setSplitNum(length, numSplits); // 调用JNI的接口函数setSplitNum()
			long offset = 0;
			long size = 8;
			long flag = PROCESS_NO_ERROR;
			boolean myFlag = true;
			while (myFlag && (flag == PROCESS_NO_ERROR)) {
				buffer = new byte[(int)(size)];
				try {
					in.readFully(offset, buffer);
				} catch (Exception e) {
					// TODO: handle exception
					myFlag = false;
				}
				if (myFlag) {
					offsetAndSize = GetVideoInfo.getVideoOffsetAndSizeAndFlag(buffer, offset, size); // 调用JNI的接口函数getVideoOffsetAndSizeAndFlag()
					flag = offsetAndSize[0];
					offset = offsetAndSize[1];
					size = offsetAndSize[2];
				}
			}
			in.close();
			if (flag == PROCESS_COMPLETE) {
				System.out.println("=== << the MP4 file processes ok ! >> ===");
			}
			else if (flag == PROCESS_ERROR) {
				System.out.println("=== << the MP4 file processes error ! >> ===");
			}
			
			// 调用JNI的接口函数getSplitAndGOPInfo()，获取该视频文件的所有的分片及每个分片的GOP的信息。
			splitAndGOPInfo = GetVideoInfo.getSplitAndGOPInfo(); 
			BlockLocation[] blkLocations = fs.getFileBlockLocations(file, 0, length);
			if (isSplitable(fs, path)) {
				System.out.println("*** the mp4 file is splited to : " + numSplits + " splits");
				Iterator<ArrayList<Long>> splitIterator = splitAndGOPInfo.keySet().iterator();
				int i = 0;
				while (splitIterator.hasNext()) {
					splitInfo = splitIterator.next();
					int blkIndex = getBlockIndex(blkLocations, splitInfo.get(0));
					splitSize = splitInfo.get(1);
					System.out.println("*** the split  " + i + "  splitSize : " + splitInfo.get(1));
					
					//添加自定义的分片信息，分片CustomInputSplit中包含GOP的信息
					splits.add(
						new CustomInputSplit(path, splitInfo.get(0), splitSize, blkLocations[blkIndex].getHosts(), splitAndGOPInfo.get(splitInfo))
					       );
					System.out.println("*** the split  " + i + "  GOP numbers : " + 
					          splitAndGOPInfo.get(splitInfo).size());
					i ++;
				}
			} else if (length != 0) { // not split.
				 int blkIndex = getBlockIndex(blkLocations, 0);
				 splits.add(new CustomInputSplit(path, 0, length, blkLocations[blkIndex].getHosts()));
				 }
		} else { //Create empty hosts array for zero length files.
			splits.add(new CustomInputSplit(path, 0, length, new String[0]));
		}	
	}
	sw.stop();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Total # of splits generated by getSplits: " + splits.size()
             + ", TimeTaken: " + sw.elapsedMillis());
        }
        return splits.toArray(new CustomInputSplit[splits.size()]);  // cast to CustomInputSplit
    }


    public RecordReader<NullWritable,BytesWritable> getRecordReader(InputSplit genericSplit,    
                            JobConf job, Reporter reporter) throws IOException{    
        reporter.setStatus(genericSplit.toString());    
        
        //创建自定义的ContentRecordReader，里面含有自定义的分片CustomInputSplit
        ContentRecordReader contentRecordReder = new ContentRecordReader(job,(CustomInputSplit)genericSplit);  
        return (RecordReader<NullWritable, BytesWritable>) contentRecordReder;  
    }
}

