package durbin.util;

import java.util.*;
import java.io.*;
import java.lang.*;

// Parallel colt versions of these routines...
// I saw no speedup on MacBook Pro when using 
// these... would be interesting to try on a many core cpu.
// import cern.colt.matrix.tobject.*;
// import cern.colt.matrix.tobject.impl.*;
// import cern.colt.list.tdouble.*;

import cern.colt.matrix.*;
import cern.colt.matrix.impl.*;
import cern.colt.list.*;
import cern.colt.matrix.impl.AbstractMatrix2D;

import groovy.lang.*;
import groovy.lang.Closure;
import groovy.lang.IntRange;
import org.codehaus.groovy.runtime.*;

//import org.codehaus.groovy.runtime.DefaultGroovyMethodsSupport.RangeInfo;

/***
* An iterator for a row or a column of a table
*/
class TableMatrix1DIterator implements Iterator{
  TableMatrix1D table;
  int idx = 0;
  
  TableMatrix1DIterator(TableMatrix1D t){
    table = t;
  }
  
  public boolean hasNext(){
    if (idx < table.size()) return(true);
    else return(false);
  }
  
  public Object next(){
    Object rval = table.get(idx);
    idx++;
    return(rval);
  }
  public void remove(){}
}

/***
* A row or a column in the table.   Specifically wrapper to make the 
* ObjectMatrix1D returned by matrix.viewColumn/Row 
* into something that is iterable, since ObjectMatrix1D doesn't
* implement iterable (probably because colt is old as dirt). <br>
* 
* KJD: I've gone off the deep end here... I'm sure this is not
* the Right Way to do this, although it works...
* all of this probably should have been handled  from the Table.groovy 
* wrapper, using some kind of expando magic or some such...<br>
* 
* Ugh... even worse, all this hasn't helped performance compared to 
* getting a copy of the array for each row... 
*/
class TableMatrix1D extends DefaultGroovyMethodsSupport implements Iterable{
  
  ObjectMatrix1D data;
	public HashMap<String,Integer> names2Idx;
	public String name;
  
  //public TableMatrix1D(ObjectMatrix1D dom){
  //  data = dom;
  //}

	public TableMatrix1D(ObjectMatrix1D dom,HashMap<String,Integer> n2I,String theName){
		names2Idx = n2I;
		name = theName;
    data = dom;
  }

	public Object asType(Class clazz) {
		if (clazz.equals(java.util.ArrayList.class)) {			
			ArrayList rval = new ArrayList();
			for(int i = 0;i < data.size();i++){
				rval.add(data.get(i));
			}
			return(rval);			
		}else if ((clazz.equals(java.util.Set.class) ||
							 clazz.equals(java.util.HashSet.class))){
			HashSet rval = new HashSet();
			for(int i = 0;i < data.size();i++){
				rval.add(data.get(i));
			}
			return(rval);
		}else{
			String msg = "Can't cast TableMatrix1D to "+clazz;
			throw new ClassCastException(msg);
		}
	}

	
  
  public long size(){ return(data.size()); }
  public Object get(int idx){ return(data.get(idx)); }  
  public void set(int idx,Object value){ data.set(idx,value); }
  

  public TableMatrix1DIterator iterator(){
    return(new TableMatrix1DIterator(this));
  }

	public Object getAt(String colName){
    int cidx = names2Idx.get(colName);
		//System.err.println("colName:"+colName+" index: "+cidx);
    return(data.get(cidx));
  }
  
  public Object getAt(int idx){
    if (idx < 0) idx =(int) data.size()+idx; // 5 -1 = 4
    return(data.get(idx));
  }

	public double[] toDoubleArray(){
		double[] rval = new double[(int)size()];
		for(int i =0;i < size();i++){
			rval[i] = (Double) getAt(i);
		}
		return(rval);
	}
    
	public String toString(){
		StringBuffer rval = new StringBuffer();
		for(int i = 0;i < size();i++){
			rval.append(getAt(i));
		}
		return(rval.toString());
	}
  
  /****************************************************
  * Returns a view corresponding to the given range. 
  */ 
  public TableMatrix1D getAt(IntRange r){
    
    // KJD:  This range is coming in with from/to swapped, but why? 
    // HACK ALERT: Until I understand why, I'm just going to swap them 
    // back... so there...  
    IntRange r2 = new IntRange(r.getToInt(),r.getFromInt());
    
    // Convert Groovy relative range values (e.g. -2) into actual 
    // range numbers...
    RangeInfo ri = DefaultGroovyMethodsSupport.subListBorders((int)this.size(),r2);        
    int start = ri.from;
    int width = ri.to-start; 
    return(new TableMatrix1D(data.viewPart(start,width),names2Idx,name));
  }  
}

/***
* A 2D table of objects with methods to read from file, iterate over
* rows or columns, and support for Groovy closures.  A Table is a 
* 2D collection of cells.  Table cells can be accessed by index or by 
* name.<br><br>
* 
* Note:  I intended for this to be a high performance 2D table that could
* be accessed by row/column index or by row/column name.  However, in practice
* I am using Multidimensional map for most things now, with adequate performance. 
* This is still used in a few places, though. 
*	
*	Note: There is a specialized table, DoubleTable, that is a more efficient version
*	of table when the data is all numeric.  DoubleTable has an easier to use API.  Eventually
*	the api for Table should be made to match and be made into an interface with two 
*	implementations:
*	
*	Table ->  DoubleTable
*	          ObjectTable
*	
*/
public class Table extends GroovyObjectSupport{

	// An somewhat efficient place to store the data...
	public ObjectMatrix2D matrix;
	public String[] colNames;
	public String[] rowNames;
	public int numCols;
	public int numRows;
	
	public int colOffset = 1; // Default doesn't include first column in table. 
	boolean bFirstColInTable = false;	
		
  public HashMap<String,Integer> colName2Idx = new HashMap<String,Integer>();
  public HashMap<String,Integer> rowName2Idx = new HashMap<String,Integer>();
  
  public Table(){}
  
	public Table(DoubleTable t){
		numRows = t.rows();
		numCols = t.cols();
		matrix = new DenseObjectMatrix2D(numRows,numCols);
		colNames = t.colNames.clone();
		rowNames = t.rowNames.clone();
		for(int r = 0;r < numRows;r++){
			for(int c = 0;c < numCols;c++){
				matrix.setQuick(r,c,t.matrix.getQuick(r,c));
			}
		}
	}
	
  public Table(int rows,int cols){
    numRows = rows;
    numCols = cols;
    // Create an empty object matrix...
	  matrix = new DenseObjectMatrix2D(numRows,numCols);
  }  
  
  public Table(String fileName,String delimiter) throws Exception{
    readFile(fileName,delimiter);
  }


	public Table(String fileName,String delimiter,boolean bFirstRowInTable) throws Exception{
		setFirstColInTable(bFirstRowInTable);
    readFile(fileName,delimiter);
  }

  public Table(String fileName) throws Exception{
    readFile(fileName,"\t");
  }
  
  public Table(String fileName,Closure c) throws Exception{
    readFile(fileName,"\t",c);
  }
  
  
  /***
  * Create and read a table from a file, applying the closure to each cell 
  * in the table as it is read and before it is saved to the table (e.g. to 
  * parse out a substring of each cell, or convert to Double). 
  */ 
  public Table(String fileName,String delimiter,Closure c) throws Exception{
     readFile(fileName,delimiter,c);
   }

	public Table(ArrayList<String> rNames,ArrayList<String> cNames){
		numRows = rNames.size();
		numCols = cNames.size();
		matrix = new DenseObjectMatrix2D(numRows,numCols);

		rowNames = new String[numRows];
		for(int i = 0;i < rNames.size();i++){
			rowNames[i] = rNames.get(i);
		}

		colNames = new String[numCols];
		for(int i = 0;i < cNames.size();i++){
			colNames[i] = cNames.get(i);
		}

		createNameMap(colNames,colName2Idx);
		createNameMap(rowNames,rowName2Idx);
	}
	
	
	/***
	* Return a transposed copy of this table. 
	*/ 
	public Table addRow(Collection<Object> row,int rowIdx,String rowName){
		Table newt = new Table(rows()+1,cols());
		newt.colNames = colNames.clone();
		newt.rowNames = new String[rows()+1];
		int offset = 0;
		for(int r =0;r < rows()+1;r++){			
			if (r == rowIdx){
				newt.rowNames[r] = rowName;
				int colIdx =0;
				for(Object val : row){
					newt.matrix.setQuick(r,colIdx++,val);
				}				
				offset = 1; // After inserted row, need to shift index by one
			}else{
				int oldrow = r - offset;
				newt.rowNames[r] = rowNames[oldrow];
				for(int c = 0;c < cols();c++){
					newt.matrix.setQuick(r,c,matrix.getQuick(oldrow,c));			
				}
			}
		}
		return(newt);		
	}	
		
	/***
	* Return a transposed copy of this table. 
	*/ 
	public Table addCol(Collection<Object> col,int colIdx,String colName){
		Table newt = new Table(rows(),cols()+1);
		newt.rowNames = rowNames.clone();
		newt.colNames = new String[cols()+1];
		int offset = 0;
		for(int c =0;c < cols()+1;c++){			
			if (c == colIdx){
				newt.colNames[c] = colName;
				int rowIdx =0;
				for(Object val : col){
					newt.matrix.setQuick(rowIdx++,c,val);
				}				
				offset = 1; // After inserted row, need to shift index by one
			}else{
				int oldcol = c - offset;
				newt.colNames[c] = colNames[oldcol];
				for(int r = 0;r < rows();r++){
					newt.matrix.setQuick(r,c,matrix.getQuick(r,oldcol));			
				}
			}
		}
		return(newt);		
	}	
	
	/**
	* Reorders rows according to the given list.
	*/
	public Table orderRowsBy(List newOrder){			
		Table newt = new Table(rows(),cols());
		newt.colNames = colNames.clone();
		newt.rowNames = new String[rows()];
		for(int r =0;r < rows();r++){
			int oldr = ((int)newOrder.get(r)) -1;
			newt.rowNames[r] = rowNames[oldr];
			for(int c = 0;c < cols();c++){
				newt.matrix.setQuick(r,c,matrix.getQuick(oldr,c));			
			}
		}
		return(newt);
	}
	
	/**
	* Reorders columns according to the given list.
	*/
	public Table orderColumnsBy(List newOrder){			
		Table newt = new Table(rows(),cols());
		newt.rowNames = rowNames.clone();
		newt.colNames = new String[cols()];
		for(int c =0;c < cols();c++){
			int oldc = ((int)newOrder.get(c)) -1;
			newt.colNames[c] = colNames[oldc];
			for(int r = 0;r < rows();r++){
				newt.matrix.setQuick(r,c,matrix.getQuick(r,oldc));			
			}
		}
		return(newt);
	}
	
	public Table transpose(){
		Table ttable = new Table(this.numCols,this.numRows);
		ttable.rowNames = colNames.clone();
		ttable.colNames = rowNames.clone();
		 ObjectMatrix2D diceView = matrix.viewDice();
		ttable.matrix = diceView.copy();
		return(ttable);
	}
	
	public void assign(Object o){
		matrix.assign(o);
	}
  
	public int rows() {
		return(numRows);
	}
	public int cols() {
		return(numCols);
	}
	
	
	
	
	public void setFirstColInTable(boolean firstColInTable){		
		// The first row will always populate the rowNames, but sometimes we want
		// to put the first row in the table itself (e.g. when stuffing into JTable)
		bFirstColInTable = firstColInTable;
		if(bFirstColInTable){
			colOffset = 0;			
		}else{
			colOffset = 1;
		}
	}
	
	/***
  * Parse the column names from a line. 
  */ 
  public String[] parseColNames(String line,String regex){
    // Not quite right, because includes spurious 0,0 column. 
		String[] fields = line.split(regex,-1); // -1 to include empty cols.
				
		String[] colNames = new String[fields.length - colOffset];
		
		for(int i = colOffset;i < fields.length;i++){
		  //System.err.println("i-colOffset: "+(i-colOffset)+" i :"+i);
		  colNames[i-colOffset] = (fields[i]).trim();
		}				
		return(colNames);
	}
	
	
	/***
  * Convenience method to initialize a name2Idx map from an array of Strings
  * 
  */ 
	public static void createNameMap(String[] names,HashMap<String,Integer> name2IdxMap){	  
	  for(int i = 0;i < names.length;i++){
	    name2IdxMap.put(names[i],i);
	  }
	}

  /***
  * Write table to a file
  */ 
	public void write(String fileName,String delimiter) throws Exception {
		BufferedWriter out = new BufferedWriter(new FileWriter(fileName));
		write(out,delimiter);
		out.close();
	}

  public String toString(){
    String delimiter = "\t";
    StringBuilder sb = new StringBuilder();
    // Write first line of column names...
    sb.append("feature_name\t");
   	for(int c = 0;c < (numCols-1);c++){
   	  sb.append(colNames[c]+delimiter);
   	}
   	sb.append(colNames[numCols-1]);
   	sb.append("\n");

   	for (int r = 0;r < numRows;r++) {
   	  // First column of each line is a row name...
   	  sb.append(rowNames[r]+delimiter);
   		for (int c = 0;c < (numCols -1);c++) {
   			Object entry = matrix.getQuick(r,c);
   			sb.append(entry+delimiter);
   		}
   		Object entry = matrix.getQuick(r,(numCols-1));
			// Don't tack on \n to last row...
			if (r == (numRows-1)) sb.append(entry);
			else sb.append(entry+"\n");
   	}
   	return(sb.toString());		
   }


  /***
  * Write table to a file
  */ 
	public void write(BufferedWriter br,String delimiter) throws Exception{	  	  
	  // Write first line of column names...
	  br.write("rowName"+delimiter);
	  for(int c = 0;c < (numCols-1);c++){
	    String str = colNames[c]+delimiter;
	    br.write(str);
	  }
	  br.write(colNames[numCols-1]+"\n");
	  	  
		for (int r = 0;r < numRows;r++) {
		  // First column of each line is a row name...
		  br.write(rowNames[r]+delimiter);
			for (int c = 0;c < (numCols -1);c++) {
				Object entry = matrix.getQuick(r,c);
        br.write(entry+delimiter);
			}
			Object entry = matrix.getQuick(r,(numCols-1));
      br.write(entry+"\n");
		}		
	}
	
	/***
	*  Read a delimited table from a file.
	*
	*  Some attention has been paid to performance, since this is meant to be
	*  a core class.  Additional performance gains are no doubt possible.
	*/
	public void readFile(String fileName,String regex) throws Exception {

		numRows = FileUtils.fastCountLines(fileName) -1; // -1 exclude heading.
		rowNames = new String[numRows];

		// Read the col headings and figure out the number of columns in the table..
		BufferedReader reader = new BufferedReader(new FileReader(fileName));
		String line = reader.readLine();

		colNames = parseColNames(line,regex);
		createNameMap(colNames,colName2Idx);
		numCols = colNames.length; 

		System.err.print("Reading "+numRows+" x "+numCols+" table...");

		// Create an empty object matrix...
		matrix = new DenseObjectMatrix2D(numRows,numCols);

		// Populate the matrix with values...
		int rowIdx = 0;
		while ((line = reader.readLine()) != null) {
			String[] tokens = line.split(regex,-1);
			if (bFirstColInTable){
				rowNames[rowIdx] = "Row "+rowIdx;
			}else{
				rowNames[rowIdx] = tokens[0].trim();
			}
									      
      for(int colIdx = 0;colIdx < (tokens.length-colOffset);colIdx++){
				//System.err.println("rowIdx:"+rowIdx+" colIdx:"+colIdx+" colOffset:"+colOffset+" tokens.length:"+tokens.length);
        matrix.setQuick(rowIdx,colIdx,tokens[colIdx+colOffset]);                
      }     
			rowIdx++;
		}
		createNameMap(rowNames,rowName2Idx);
		System.err.println("done");
	}
	
	public void readFile(String fileName,Closure c) throws Exception {
	  readFile(fileName,"\t",c);
	}
	

	
  /***
	*  Read a delimited table from a file.
	*  Same as other readFile, except this one accepts a closure 
	*  to apply to each value before storing it in the matrix.
	*/
	public void readFile(String fileName,String regex,Closure c) throws Exception {

		numRows = FileUtils.fastCountLines(fileName) -1; // -1 exclude heading.
		rowNames = new String[numRows];

		// Read the col headings and figure out the number of columns in the table..
		BufferedReader reader = new BufferedReader(new FileReader(fileName));
		String line = reader.readLine();
		colNames = parseColNames(line,regex);
		createNameMap(colNames,colName2Idx);
		numCols = colNames.length;

		System.err.print("Reading "+numRows+" x "+numCols+" table...");

		// Create an empty object matrix...
		matrix = new DenseObjectMatrix2D(numRows,numCols);

    // Populate the matrix with values...
  	int rowIdx = 0;
  	while ((line = reader.readLine()) != null) {
  		String[] tokens = line.split(regex,-1); 
			if (bFirstColInTable){
				rowNames[rowIdx] = "Row "+rowIdx;
			}else{
				rowNames[rowIdx] = tokens[0].trim();
			} 		  		

      for(int colIdx = 0;colIdx < (tokens.length-colOffset);colIdx++){
        matrix.setQuick(rowIdx,colIdx,c.call(tokens[colIdx+colOffset]));                
      }     
  		rowIdx++;
  	}
		createNameMap(rowNames,rowName2Idx);
		System.err.println("done");
	}
	
	

	public Object get(int row,int col) {
		return(matrix.getQuick(row,col));
	}
	
	public void set(int row,int col,Object data){
	  matrix.setQuick(row,col,data);
	}
	
	public void set(String rowStr,String colStr,Object data){
	  int row = getRowIdx(rowStr);
	  int col = getColIdx(colStr);
	  matrix.setQuick(row,col,data);
	}
	
	public boolean containsCol(String colName){
		return(colName2Idx.keySet().contains(colName));
	}
	
	public boolean containsRow(String rowName){
		return(rowName2Idx.keySet().contains(rowName));
	}
	
	
	public List<Integer> getRowIndicesContaining(String substring){
	  ArrayList<Integer> rvals = new ArrayList<Integer>();
	  for(int r = 0;r < numRows;r++){
	    if (rowNames[r].contains(substring)){
	      rvals.add(r);
	    }
	  }
	  return(rvals);
	}
	
	public List<Integer> getColIndicesContaining(String substring){
	  ArrayList<Integer> rvals = new ArrayList<Integer>();
	  for(int c = 0;c < numCols;c++){
	    if (colNames[c].contains(substring)){
	      rvals.add(c);
	    }
	  }
	  return(rvals);
	}
	
	
	public int getRowIdx(String row){return(rowName2Idx.get(row));}
	public int getColIdx(String col){return(colName2Idx.get(col));}
	
	/***
	*
	*/
  public DoubleArrayList getRowAsDoubleArrayList(int row){
    DoubleArrayList dal = new DoubleArrayList();
    ObjectMatrix1D dm1D  = matrix.viewRow(row);
    for(int r = 0;r < dm1D.size();r++){
      dal.add((Double)dm1D.get(r));
    }
    return(dal);
  }
  
  /***
	*
	*/
  public DoubleArrayList getColAsDoubleArrayList(int col){
    DoubleArrayList dal = new DoubleArrayList();
    ObjectMatrix1D dm1D  = matrix.viewColumn(col);
    for(int r = 0;r < dm1D.size();r++){
      dal.add((Double)dm1D.get(r));
    }
    return(dal);
  }
	
	public Object[] getRowAsArray(int row){
	  return(matrix.viewRow(row).toArray());
	}
		
	public Object[] getColAsArray(int col){
	  return(matrix.viewColumn(col).toArray());
	}
		
	public TableMatrix1D getRow(int row){
	   return(new TableMatrix1D(matrix.viewRow(row),colName2Idx,rowNames[row]));
	}
	
	public TableMatrix1D getAt(int ridx){
    return(getRow(ridx));
  }
		
  public TableMatrix1D getAt(String rowName){
		//System.err.println("rowName = "+rowName);
    int ridx = getRowIdx(rowName);
		//System.err.println("ridx = "+ridx);
    return(getRow(ridx));
  }
	
	
	public TableMatrix1D getCol(int col){
	   return(new TableMatrix1D(matrix.viewColumn(col),rowName2Idx,colNames[col]));
	}
	
	public TableMatrix1D getCol(String colStr){
		int col = getColIdx(colStr);
		return(new TableMatrix1D(matrix.viewColumn(col),rowName2Idx,colNames[col]));
	}
	
	public TableMatrix1D getRow(String rowStr){
		int row = getRowIdx(rowStr);
		return(new TableMatrix1D(matrix.viewRow(row),colName2Idx,rowNames[row]));
	}

	/***
	* Provide support for iterating over table by rows...
	*/
	public Table each(Closure closure) {
		for (int r = 0;r < numRows;r++) {
			for (int c = 0;c < numCols;c++) {
				Object entry = matrix.getQuick(r,c);
				closure.call(new Object[] {r,c,entry});
			}
		}
		return this;
	}

	/***
	* Provide support for iterating over table by rows...
	*/
	public Table eachByRows(Closure closure) {
		for (int r = 0;r < numRows;r++) {
			for (int c = 0;c < numCols;c++) {
				Object entry = matrix.getQuick(r,c);
				closure.call(new Object[] {r,c,entry});
			}
		}
		return this;
	}


	/***
	* Provide support for iterating over table by columns...
	*/
	public Table eachByCols(Closure closure) {
		for (int c = 0;c < numCols;c++) {
			for (int r = 0;r < numRows;r++) {
				Object entry = matrix.getQuick(r,c);
				closure.call(new Object[] {c,r,entry});
			}
		}
		return this;
	}

	/***
	* Provide support for iterating over columns
	*
	* Note: I should be able to provide my own
	* column view object that supports iteration so
	* that I don't have to pay the cost of making a toArray
	* copy.
	*/
	public Table eachColumn(Closure closure) {
		for (int c = 0;c < numCols;c++) {
			//Object[] column = matrix.viewColumn(c).toArray();			
			TableMatrix1D column = new TableMatrix1D(matrix.viewColumn(c),rowName2Idx,colNames[c]);			
			closure.call(new Object[] {column});
		}
		return this;
	}

	/***
	* Provide support for iterating over rows
	*/
	public Table eachRow(Closure closure) {
		for (int r = 0;r < numRows;r++) {
			//Object[] row = matrix.viewRow(r).toArray();  bit costly to make a copy...
			TableMatrix1D row = new TableMatrix1D(matrix.viewRow(r),colName2Idx,rowNames[r]);
			closure.call(new Object[] {row});
		}
		return this;
	}
	
	/***
	* Crude tests. 
	*/ 
	public static void main(String args[]) throws Exception {
	  Table tc = new Table();
	  tc.readFile(args[0],"\t");
	  System.gc();

	  Double onevalue = (Double) tc.matrix.getQuick(500,500);
	  System.err.println("One value: "+onevalue);
  }
  
  	
	/***
	*  DEPRECATED SLOW (MAY TRY TO SPEED UP)
	*  Read a delimited table from a file.
	*
	*  Some attention has been paid to performance, since this is meant to be
	*  a core class.  Additional performance gains are no doubt possible.
	*/
	public void readFile0(String fileName,String regex) throws Exception {

		numRows = FileUtils.fastCountLines(fileName) -1; // -1 exclude heading.
		rowNames = new String[numRows];

		// Read the col headings and figure out the number of columns in the table..
		BufferedReader reader = new BufferedReader(new FileReader(fileName));
		String line = reader.readLine();

		colNames = parseColNames(line,regex);
		createNameMap(colNames,colName2Idx);
		numCols = colNames.length; 

		System.err.print("Reading "+numRows+" x "+numCols+" table...");

		// Create an empty object matrix...
		matrix = new DenseObjectMatrix2D(numRows,numCols);

    // Populate the matrix with values...
  	int rowIdx = 0;
  	while ((line = reader.readLine()) != null) {
  	  //		StringTokenizer parser = new StringTokenizer(line,"\t");
  	  Scanner sc = new Scanner(line).useDelimiter(regex);
  	  rowNames[rowIdx] = sc.next().trim();
  	  int colIdx = 0;
  	  while (sc.hasNext()) {
  	    String next = sc.next();
  		  matrix.setQuick(rowIdx,colIdx,sc.next());
  			colIdx++;
  		}
  	  rowIdx++;
  	}
		createNameMap(rowNames,rowName2Idx);
		System.err.println("done");
	}
}


//Put split back in, because StringTokenizer doesn't handle missing values 
//easily. 
//time ./testscripts/tabletest.groovy data/chinSFGenomeBio2007_data_overlap.tab
//Reading 8566 x 220 table...done
//
//real	0m3.452s

// With Scanner (naive, new Scanner(line))
// time ./testscripts/tabletest.groovy data/chinSFGenomeBio2007_data_overlap.tab
// Reading 8566 x 220 table...done
// real	0m5.739s


// time ./scripts/tab2arff -d data/chinSFGenomeBio2007_data_overlap.tab > data.arff/test.arff
// Reading 8566 x 220 table...done
// Writing output...
// 
// real	1m6.706s
// 
// Damn.  No better than copying each slice [0..-2] as an array... so so far all of this
// fancy TableMatrixIterator stuff isn't doing me any good at all...
// 
// With ArffHelper time drops to 20.669s!
// With ArffHelper and a BufferedWriter, drops to 10-11 sec.   
//
// 


// Performance 220 x 8566 file
//
// line.split()                  2.07s
// w/o any split
// StringTokenizer               1.75s
// StringTokenizer via Groovy    3.25s
// StringTokenizer getBytes      2.79s


// ***NOTE MISTAKEN BAD PERFORMANCE RESULTS
// Below times are with compilethreshold set to 0 and via Groovy.  Apparently that really burns up the time.
// Performance 220 x 8566 file
//
// line.split()     23.47
// w/o any split     9.17  (implication... split consumes 14 seconds of 23 seconds)
// StringTokenizer  16.05  (implication... tokenizer consumes 7 seconds... so 2x fast as split)



// RAM Performance
//
// Theoretical:
// 8566 x 220 = 1,884,520 array elements.
//
//                         0123456789012345678
// Each string is about... -0.046845,-0.057836   20 characters long.
//
// So raw total space about: 37,690,400.
//
// Base memory for dense matrix:  8*rows()*columns() = 15,076,160
//
// Total memory with no funny business:  52,766,560.
//
// Final heap size from top:
//
// PID COMMAND      %CPU   TIME   #TH #PRTS #MREGS RPRVT  RSHRD  RSIZE  VSIZE
// 10036 java        98.9%  0:29.19  13   115    371  330M  6116K   336M  1006M
//
// Apparent memory (336M?):  6x this amount.
//
// A length 10 string, BTW, takes 56 bytes!!  Not sure what a 20 character string
// takes, but at 10 characters we're 5.6x over the character count, which is
// enough to account for the added space.
//



