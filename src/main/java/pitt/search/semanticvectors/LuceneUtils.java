/**
 * Copyright (c) 2007, University of Pittsburgh
 * <p>
 * All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 * <p>
 * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * <p>
 * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution.
 * <p>
 * Neither the name of the University of Pittsburgh nor the names
 * of its contributors may be used to endorse or promote products
 * derived from this software without specific prior written
 * permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 **/

package pitt.search.semanticvectors;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.BaseCompositeReader;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.SlowCompositeReaderWrapper;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.FieldInfos;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

import pitt.search.semanticvectors.utils.StringUtils;
import pitt.search.semanticvectors.utils.VerbatimLogger;

/**
 * Class to support reading extra information from Lucene indexes,
 * including term frequency, doc frequency.
 */
public class LuceneUtils {
  public static final Version LUCENE_VERSION = Version.LUCENE_6_6_0;

  private static final Logger logger = Logger.getLogger(DocVectors.class.getCanonicalName());
  private FlagConfig flagConfig;
  private BaseCompositeReader<LeafReader> compositeReader;
  private LeafReader leafReader;
  private ConcurrentHashMap<String, Float> termEntropy = new ConcurrentHashMap<String, Float>();
  private ConcurrentHashMap<String, Float> termIDF = new ConcurrentHashMap<String, Float>();
  private ConcurrentHashMap<String, Integer> termFreq = new ConcurrentHashMap<String, Integer>();
  private boolean totalTermCountCaching = true;
  private TreeSet<String> stopwords = null;
  private TreeSet<String> startwords = null;

  /**
   * Determines which term-weighting strategy to use in indexing, 
   * and in search if {@link FlagConfig#usetermweightsintermsearch()} is set.
   *
   * <p>Names may be passed as command-line arguments, so underscores are avoided.
   */
  public enum TermWeight {
    /** No term weighting: all terms have weight 1. */
    NONE,
    /** Use inverse document frequency: see {@link LuceneUtils#getIDF}. */
    IDF,
    /** Use log entropy: see {@link LuceneUtils#getEntropy}. */
    LOGENTROPY,
    /** Use term frequency: see {@link LuceneUtils#getGlobalTermFreq} */
    FREQ,
    /** Use square root of term frequency. */
    SQRT,
    /** Use log of term frequency. */
    LOGFREQ,
  }

  /**
   * @param flagConfig Contains all information necessary for configuring LuceneUtils.
   *        {@link FlagConfig#luceneindexpath()} must be non-empty. 
   */
  public LuceneUtils(FlagConfig flagConfig) throws IOException {
    if (flagConfig.luceneindexpath().isEmpty()) {
      throw new IllegalArgumentException(
          "-luceneindexpath is a required argument for initializing LuceneUtils instance.");
    }

    this.compositeReader = DirectoryReader.open(
        FSDirectory.open(FileSystems.getDefault().getPath(flagConfig.luceneindexpath())));
    this.leafReader = SlowCompositeReaderWrapper.wrap(compositeReader);
    MultiFields.getFields(compositeReader);
    this.flagConfig = flagConfig;
    if (!flagConfig.stoplistfile().isEmpty())
      loadStopWords(flagConfig.stoplistfile());

    if (!flagConfig.startlistfile().isEmpty())
      loadStartWords(flagConfig.startlistfile());

    VerbatimLogger.info("Initialized LuceneUtils from Lucene index in directory: " + flagConfig.luceneindexpath() + "\n");
    VerbatimLogger.info("Fields in index are: " + String.join(", ", this.getFieldNames()) + "\n");
  }

  /**
   * Loads the stopword file into the {@link #stopwords} data structure.
   * @param stoppath Path to stopword file.
   * @throws IOException If stopword file cannot be read.
   */
  public void loadStopWords(String stoppath) throws IOException {
    logger.info("Using stopword file: " + stoppath);
    stopwords = new TreeSet<String>();
    try {
      BufferedReader readIn = new BufferedReader(new FileReader(stoppath));
      String in = readIn.readLine();
      while (in != null) {
        stopwords.add(in);
        in = readIn.readLine();
      }
      readIn.close();
    } catch (IOException e) {
      throw new IOException("Couldn't open file " + stoppath);
    }
  }

  /**
   * Loads the startword file into the {@link #startwords} data structure.
   * @param startpath Path to startword file
   * @throws IOException If startword file cannot be read.
   */
  public void loadStartWords(String startpath) throws IOException {
    startwords = new TreeSet<String>();
    try {
      BufferedReader readIn = new BufferedReader(new FileReader(startpath));
      String in = readIn.readLine();
      while (in != null) {
        startwords.add(in);
        in = readIn.readLine();
      }
      VerbatimLogger.info(String.format(
          "Loading startword file: '%s'. Only these %d words will be indexed.\n",
          startpath, startwords.size()));
      readIn.close();
    } catch (IOException e) {
      throw new IOException("Couldn't open file " + startpath);
    }
  }

  /**
   * Returns true if term is in stoplist, false otherwise.
   */
  public boolean stoplistContains(String x) {
    if (stopwords == null) return false;
    return stopwords.contains(x);
  }

  /**
   * Returns false if term is not in startlist, true otherwise (including if no startlist exists).
   */
  public boolean startlistContains(String x) {
    if (startwords == null) return true;
    return startwords.contains(x);
  }

  public Document getDoc(int docID) throws IOException {
    return this.leafReader.document(docID);
  }

  public String getExternalDocId(int docID) throws IOException {

    //to save time, avoid using external ID if so desired
    if (flagConfig.docidfield().equals("luceneID")) return docID + "";

    String externalDocId;
    try {
      externalDocId = this.getDoc(docID).getField(flagConfig.docidfield()).stringValue();
    } catch (IOException | NullPointerException e) {
      logger.severe(String.format(
          "Failed to get external doc ID from doc no. %d in Lucene index." +
              "\nThis is almost certain to lead to problems." +
              "\nCheck that -docidfield was set correctly and exists in the Lucene index",
          docID));
      throw e;
    }

    return externalDocId;
  }

  /**
   * Gets the terms for a given field. Throws {@link java.lang.NullPointerException} if this is null.
   */
  public Terms getTermsForField(String field) throws IOException {
    Terms terms = leafReader.terms(field);
    if (terms == null) {
      throw new NullPointerException(String.format(
          "No terms for field: '%s'.\nKnown fields are: '%s'.", field, StringUtils.join(this.getFieldNames())));
    }
    return leafReader.terms(field);
  }

  public PostingsEnum getDocsForTerm(Term term) throws IOException {
    return this.leafReader.postings(term);
  }

  public Terms getTermVector(int docID, String field) throws IOException {
    return this.leafReader.getTermVector(docID, field);
  }

  public FieldInfos getFieldInfos() {
    return this.leafReader.getFieldInfos();
  }

  public List<String> getFieldNames() {
    List<String> fieldNames = new ArrayList<>();
    for (FieldInfo fieldName : this.leafReader.getFieldInfos()) {
      fieldNames.add(fieldName.name);
    }
    return fieldNames;
  }

  /**
   * Gets the document frequency of a term,
   * i.e. how may documents it occurs in the whole corpus
   * @param term whose frequency you want
   * @return Global document frequency of term, or 1 if unavailable.
   */
  public int getGlobalDocFreq(Term term) {
    try {
      return compositeReader.docFreq(term);
    } catch (IOException e) {
      // TODO Auto-generated catch block
      logger.info("Couldn't get term frequency for term " + term.text());
      return 1;
    }
  }

  /**
   * Gets the global term frequency of a term,
   * i.e. how may times it occurs in the whole corpus
   * @param term whose frequency you want
   * @return Global term frequency of term, or 1 if unavailable.
   */
  public int getGlobalTermFreq(Term term) {
    int tf = 0;
    if (totalTermCountCaching && termFreq.containsKey(term.field()+"_"+term.text())) {
      return termFreq.get(term.field()+"_"+term.text());
    } else
      try {
        tf = (int) compositeReader.totalTermFreq(term);
        termFreq.put(term.field()+"_"+term.text(), tf);
      } catch (IOException e) {
        logger.info("Couldn't get term frequency for term " + term.text());
        return 1;
      }
    if (tf == -1) {
      logger.warning("Lucene StandardDirectoryReader returned -1 for term: '"
          + term.text() + "' in field: '" + term.field() + "'. Changing to 0."
          + "\nThis may be due to a version-mismatch and might be solved by rebuilding your Lucene index.");
      tf = 0;
    }
    return tf;
  }

  /**
   * Gets a term weight for a string, adding frequency over occurrences
   * in all contents fields.
   */
  public float getGlobalTermWeightFromString(String termString) {
    float freq = 0;
    for (String field : flagConfig.contentsfields())
      freq += getGlobalTermWeight(new Term(field, termString));
    return freq;
  }

  /**
   * Gets a global term weight for a term, depending on the setting for
   * {@link FlagConfig#termweight()}.
   *
   * Used in indexing. Used in query weighting if
   * {@link FlagConfig#usetermweightsintermsearch} is true.
   *
   * @param term whose frequency you want
   * @return Global term weight, or 1 if unavailable.
   */
  public float getGlobalTermWeight(Term term) {
    switch (flagConfig.termweight()) {
      case NONE:
        return 1;
      case SQRT:
        return (float) Math.sqrt(getGlobalTermFreq(term));
      case IDF:
        return getIDF(term);
      case LOGENTROPY:
        return getEntropy(term);
      case FREQ:
        return (float) getGlobalTermFreq(term);
      case LOGFREQ:
        return (float) Math.log(getGlobalTermFreq(term));
    }
    VerbatimLogger.severe("Unrecognized termweight option: " + flagConfig.termweight()
        + ". Returning 1.\n");
    return 1;
  }

  /**
   * Gets a local term weight for a term based on its document frequency, depending on the setting for
   * {@link FlagConfig#termweight()}.
   *
   * Used in indexing. 
   *
   * @param docfreq the frequency of the term concerned in the document of interest
   * @return Local term weight
   */
  public float getLocalTermWeight(int docfreq) {
    switch (flagConfig.termweight()) {
      case NONE:
        return 1;
      case IDF:
        return docfreq;
      case LOGENTROPY:
        return (float) Math.log10(1 + docfreq);
      case SQRT:
        return (float) Math.sqrt(docfreq);
    }
    VerbatimLogger.severe("Unrecognized termweight option: " + flagConfig.termweight()
        + ". Returning 1.");
    return 1;
  }

  /**
   * Returns the number of documents in the Lucene index.
   */
  public int getNumDocs() {
    return compositeReader.numDocs();
  }

  /**
   * Gets the IDF (i.e. log10(numdocs/doc frequency)) of a term
   *  @param term the term whose IDF you would like
   */
  private float getIDF(Term term) {
    if (termIDF.containsKey(term.field()+"_"+term.text())) {
      return termIDF.get(term.field()+"_"+term.text());
    } else {
      try {
        int freq = compositeReader.docFreq(term);
        if (freq == 0) {
          return 0;
        }
        float idf = (float) Math.log10(compositeReader.numDocs() / (float) freq);
        termIDF.put(term.field()+"_"+term.text(), idf);
        return idf;
      } catch (IOException e) {
        // Catches IOException from looking up doc frequency, never seen yet in practice.
        e.printStackTrace();
        return 1;
      }
    }
  }

  /**
   * Gets the 1 - entropy (i.e. 1+ plogp) of a term,
   * a function that favors terms that are focally distributed
   * We use the definition of log-entropy weighting provided in
   * Martin and Berry (2007):
   * Entropy = 1 + sum ((Pij log2(Pij)) /  log2(n))
   * where Pij = frequency of term i in doc j / global frequency of term i
   * 		 n	 = number of documents in collection
   * @param term whose entropy you want
   * Thanks to Vidya Vasuki for adding the hash table to
   * eliminate redundant calculation
   */
  private float getEntropy(Term term) {
    if (termEntropy.containsKey(term.field()+"_"+term.text()))
      return termEntropy.get(term.field()+"_"+term.text());
    int gf = getGlobalTermFreq(term);
    double entropy = 0;
    try {
      PostingsEnum docsEnum = this.getDocsForTerm(term);
      while ((docsEnum.nextDoc()) != PostingsEnum.NO_MORE_DOCS) {
        double p = docsEnum.freq(); //frequency in this document
        p = p / gf;    //frequency across all documents
        entropy += p * (Math.log(p) / Math.log(2)); //sum of Plog(P)
      }
      int n = this.getNumDocs();
      double log2n = Math.log(n) / Math.log(2);
      entropy = entropy / log2n;
    } catch (IOException e) {
      logger.info("Couldn't get term entropy for term " + term.text());
    }
    termEntropy.put(term.field()+"_"+term.text(), 1 + (float) entropy);
    return (float) (1 + entropy);
  }

  /**
   * Public version of {@link #termFilter} that gets all its inputs from the
   * {@link #flagConfig} and the provided term.
   *
   * External callers should normally use this method, so that new filters are
   * available through different codepaths provided they pass a {@code FlagConfig}.
   *
   * @param term Term to be filtered in or out, depending on Lucene index and flag configs.
   */
  public boolean termFilter(Term term) {
    return termFilter(term, flagConfig.contentsfields(),
        flagConfig.minfrequency(), flagConfig.maxfrequency(),
        flagConfig.maxnonalphabetchars(), flagConfig.filteroutnumbers(),
        flagConfig.mintermlength());
  }

  /**
   * Filters out non-alphabetic terms and those of low frequency.
   *
   * Thanks to Vidya Vasuki for refactoring and bug repair
   *
   * @param term Term to be filtered.
   * @param desiredFields Terms in only these fields are filtered in
   * @param minFreq minimum term frequency accepted
   * @param maxFreq maximum term frequency accepted
   * @param maxNonAlphabet reject terms with more than this number of non-alphabetic characters
   */
  protected boolean termFilter(
      Term term, String[] desiredFields, int minFreq, int maxFreq, int maxNonAlphabet, int minTermLength) {
    // Field filter.
    boolean isDesiredField = false;
    for (int i = 0; i < desiredFields.length; ++i) {
      if (term.field().compareToIgnoreCase(desiredFields[i]) == 0) {
        isDesiredField = true;
      }
    }

    // Stoplist (if active)
    if (stoplistContains(term.text()))
      return false;

    // Startlist (if active)
    if (!startlistContains(term.text()))
      return false;

    if (!isDesiredField) {
      return false;
    }

    // Character filter.
    if (maxNonAlphabet != -1) {
      int nonLetter = 0;
      String termText = term.text();

      //Must meet minimum term length requirement
      if (termText.length() < minTermLength) return false;

      for (int i = 0; i < termText.length(); ++i) {
        if (!Character.isLetter(termText.charAt(i)))
          nonLetter++;
        if (nonLetter > maxNonAlphabet)
          return false;
      }
    }

    // Frequency filter.
    int termfreq = getGlobalTermFreq(term);
    if (termfreq < minFreq | termfreq > maxFreq) {
      return false;
    }

    // If we've passed each filter, return true.
    return true;
  }

  /**
   * Applies termFilter and additionally (if requested) filters out digit-only words. 
   *
   * @param term Term to be filtered.
   * @param desiredFields Terms in only these fields are filtered in
   * @param minFreq minimum term frequency accepted
   * @param maxFreq maximum term frequency accepted
   * @param maxNonAlphabet reject terms with more than this number of non-alphabetic characters
   * @param filterNumbers if true, filters out tokens that represent a number
   */
  private boolean termFilter(
      Term term, String[] desiredFields, int minFreq, int maxFreq,
      int maxNonAlphabet, boolean filterNumbers, int minTermLength) {
    // number filter
    if (filterNumbers) {
      try {
        // if the token can be parsed as a floating point number, no exception is thrown and false is returned
        // if not, an exception is thrown and we continue with the other termFilter method.
        // remark: this does not filter out e.g. Java or C++ formatted numbers like "1f" or "1.0d"
        Double.parseDouble(term.text());
        return false;
      } catch (Exception e) {
      }
    }
    return termFilter(term, desiredFields, minFreq, maxFreq, maxNonAlphabet, minTermLength);
  }
}
