/*
 * BackupJRoller creates a backup of your blog hosted on JRoller.com
 * Copyright (C) 2006 Damien Bonvillain kame@cinemasie.com
 * 
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package eu.carlossanchez.jroller;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.xml.sax.SAXException;

/**
 * Use it as such:
 * java eu.carlossanchez.jroller.BackupJRoller atom_url [base_filename]
 *
 * atom_url is the full URL on JRoller.com where the atom feed with
 * comments can be found. A template for such a feed comes along this
 * source.
 *
 * base_filename is the prefix for the backup files, 'jroller_bak' by
 * default.
 *
 * @version 1.0
 */
public class BackupJRoller {
    private static XPathExpression xpeLastDate = null;
    private static XPathExpression xpeEntries = null;
    private static XPathExpression xpeComments = null;
    private static XPathExpression xpeEntryId = null;
    private static XPathExpression xpeCommentParentId = null;
    private static DocumentBuilder domAnalyseur = null;

    public static void main(String[] argv) {
	if(argv.length < 1) {
	    dispHelp();
	    System.exit(-1);
	}
	String stringURLBase = argv[0];	
	String baseFileName = "jroller_bak";
	

	if(argv.length >= 2) {
	    baseFileName = argv[1];
	}

	// moi aussi j'aime travailler avec un gros plat de strings
	initialiseBordelXML();

	// hop, téléchargement en cours...
	int fileCount = telechargeFichiers(stringURLBase, baseFileName);
	
	// petit nettoyage
	xpeLastDate = null;

	// fusioooon
	fusionneFichiers(baseFileName, fileCount);
    }

    private static int telechargeFichiers(String stringURLBase, String baseFileName) {
	int result = 0;

	String nextDate = "";
	String previousNextDate = null;
	String stringURL = stringURLBase;
	URL url = null;

	do {
	    try {
		url = new URL(stringURL);
	    } catch (MalformedURLException mue) {
		System.err.println(mue.getMessage());
		dispHelp();
	    }
	    File target = new File(baseFileName + format(++result) + ".xml");
	    verbose("Downloading " + stringURL + " as " + target);
	    saveURL(url, target);
	    previousNextDate = nextDate;
	    nextDate = nextDate(target);
	    stringURL = stringURLBase + '/' + nextDate;
	} while(nextDate != null && nextDate.length() != 0 && !previousNextDate.equals(nextDate));

	return result;
    }

    private static void fusionneFichiers(String baseFileName, int fileCount) {
	// ouverture du premier fichier..
	File target = new File(baseFileName + format(1) + ".xml");
	Set<String> currentIds = new HashSet<String>();
	Set<String> previousIds = new HashSet<String>();

	verbose("Loading " + target);
	Document document = parse(target);
	Element feed = document.getDocumentElement();
	NodeList entrees = (NodeList)evaluate(xpeEntries, document, XPathConstants.NODESET);
	int nbEntrees = entrees.getLength();
	// debug(nbEntrees);
	    
	for(int i = 0; i < nbEntrees; ++i) {
	    String id = evaluate(xpeEntryId, entrees.item(i));
	    previousIds.add(id);
	}

	for(int j = 2; j <= fileCount; ++j) {
	    target = new File(baseFileName + format(j) + ".xml");
	    verbose("Merging " + target);
	    Document suite = parse(target);
	    entrees = (NodeList)evaluate(xpeEntries, suite, XPathConstants.NODESET);
	    nbEntrees = entrees.getLength();
	    for(int i = 0; i < nbEntrees; ++i) {
		Node noeud = entrees.item(i);
		String id = evaluate(xpeEntryId, noeud);
		currentIds.add(id);
		if(!previousIds.contains(id)) {
		    /*
		    // So easy, but completely forbidden... even what could have been simple
		    // with DOM is just butt-ugly. See http://www.w3.org/DOM/faq.html#ownerdoc
		    feed.appendChild(noeud.cloneNode(true));
		    */
		    feed.appendChild(document.importNode(noeud, true));
		} else {
		    // debug("  dropping " + id);
		}
	    }
	    entrees = (NodeList)evaluate(xpeComments, suite, XPathConstants.NODESET);
	    nbEntrees = entrees.getLength();
	    for(int i = 0; i < nbEntrees; ++i) {
		Node noeud = entrees.item(i);
		String id = evaluate(xpeCommentParentId, noeud);
		if(!previousIds.contains(id)) {
		    feed.appendChild(document.importNode(noeud, true));
		} else {
		    // debug("  dropping comment for " + id);
		}
	    }
	    previousIds = currentIds;
	    currentIds = new HashSet<String>();
	}
	
	// et hop, à la sauvegarde, toi aussi tu aimes les bonnes tartines
	// pour faire une bête sérialisation XML en 2006
	
	try {
	    DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
	    // ahahah, just kidding, completely useless without external libs...
	} catch (ClassNotFoundException cnfe) {
	    // who cares?
	} catch (InstantiationException internetExplorer) {
	    // great API design...
	} catch (IllegalAccessException iae) {
	    // you really feel the work spent on the SPI...
	}

	TransformerFactory tFactory = TransformerFactory.newInstance();
	try {
	    Transformer robotHideux = tFactory.newTransformer();
	    Properties proprietes = new Properties();
	    proprietes.put("method", "xml");
	    proprietes.put("version", "1.0"); 
	    proprietes.put("encoding", "UTF-8"); 
	    proprietes.put("standalone", "yes");
	    proprietes.put("indent", "no");
	    proprietes.put("omit-xml-declaration", "no");
	    robotHideux.setOutputProperties(proprietes);

	    File leTout = new File(baseFileName + "_all.xml");
	    FileOutputStream flux = new FileOutputStream(leTout);
	    Result sortie = new StreamResult(flux);
	    
	    robotHideux.transform(new DOMSource(document), sortie);
	    flux.close();
	    verbose(leTout + " saved");
	} catch (TransformerConfigurationException tfe) {
	    System.err.println(tfe.getMessage());
	    System.exit(-9);
	} catch (FileNotFoundException fnfe) {
	    System.err.println(fnfe.getMessage());
	    System.exit(-10);
	} catch (TransformerException te) {
	    System.err.println(te.getMessage());
	    System.exit(-11);
	} catch (IOException ioe) {
	    System.err.println(ioe.getMessage());
	    System.exit(-12);
	}
    }

    private static void saveURL(URL url, File file) {
	try {
	    HttpURLConnection conn = (HttpURLConnection)url.openConnection();
	    int status = conn.getResponseCode();
	
	    if(status == HttpURLConnection.HTTP_OK) {
		// System.out.println(conn.getContentLength());
		BufferedInputStream bis = new BufferedInputStream(conn.getInputStream());
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(file));
		final byte[] buffer = new byte[32768];
		int nbBits = 0;
		while( (nbBits = bis.read(buffer, 0, buffer.length)) != -1) {
		    // debug(nbBits);
		    bos.write(buffer, 0, nbBits);
		}
		bos.close();
		bis.close();
		// debug(conn.getContent().getClass());
	    } else {
		throw new RuntimeException(url.toString() + " " + status);
	    }
	} catch (IOException ioe) {
	    throw new RuntimeException("Unrecoverable IO error:" + ioe.getMessage());
	}
    }

    private static String nextDate(File target) {
	String result = null;
	Document document = parse(target);
	String lastDate = (String)evaluate(xpeLastDate, document);
	if ((lastDate!=null) && (lastDate.length()>0))
	    result = lastDate.substring(0,4)+lastDate.substring(5,7)+lastDate.substring(8,10);
	// debug(result);

	return result; 
    }

    private static void initialiseBordelXML() {
	DocumentBuilderFactory fabriqueDOM = DocumentBuilderFactory.newInstance();
	try {
	    domAnalyseur = fabriqueDOM.newDocumentBuilder();
	} catch (ParserConfigurationException pce) {
	    System.err.println(pce.getMessage());
	    System.exit(-5);
	}

	XPathFactory xpFactory = XPathFactory.newInstance();
	XPath xp = xpFactory.newXPath();
	try {
	    xpeLastDate = xp.compile("/feed/entry[created/text()][last()]/created/text()");
	    xpeEntries = xp.compile("/feed/entry[created/text()]");
	    xpeComments = xp.compile("/feed/entry[annotate[@type='comment']]");
	    xpeEntryId = xp.compile("./id/text()");
	    xpeCommentParentId = xp.compile("./annotate[@type='comment'][@rel='parent']/text()");
	} catch (XPathExpressionException xpee) {
	    System.err.println(xpee.getMessage());
	    System.err.println("Static XPath Expression wrong!!");
	    // xpee.printStackTrace();
	    System.exit(-3);
	}
    }
    
    private static String evaluate(XPathExpression xpe, Object target) {
	String result = null;
	try {
	    result = xpe.evaluate(target);
	} catch (XPathExpressionException xpee) {
	    System.err.println(xpee.getMessage());
	    System.exit(-3);
	}
	return result;
    }

    private static Object evaluate(XPathExpression xpe, Object target, QName qName) {
	Object result = null;
	try {
	    result = xpe.evaluate(target, qName);
	} catch (XPathExpressionException xpee) {
	    System.err.println(xpee.getMessage());
	    System.exit(-3);
	}
	return result;
    }

    private static Document parse(File target) {
	Document result = null;
	try {
	    result = domAnalyseur.parse(target);
	}  catch (FileNotFoundException fnfe) {
	    System.err.println(fnfe.getMessage());
	    System.exit(-4);
	} catch (SAXException saxe) {
	    System.err.println(saxe.getMessage());
	    System.exit(-6);
	} catch (IOException ioe) {
	    System.err.println(ioe.getMessage());
	    System.exit(-7);
	}
	return result;
    }

    private static String format(int number) {
	return Integer.toHexString(number).toUpperCase();
    }

    private static void dispHelp() {
	System.out.println("BackupJRoller atom_url [base_filename]");
    }

    private static void verbose(String s) {
	System.out.println(s);
    }
    
    private static void debug(Object o) {
	System.out.println(o);
    }
}
