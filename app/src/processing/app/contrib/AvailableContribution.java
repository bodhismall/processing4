/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  Part of the Processing project - http://processing.org

  Copyright (c) 2013-20 The Processing Foundation
  Copyright (c) 2011-12 Ben Fry and Casey Reas

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License version 2
  as published by the Free Software Foundation.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along
  with this program; if not, write to the Free Software Foundation, Inc.
  59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app.contrib;

import java.io.*;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import processing.app.*;
import processing.core.PApplet;
import processing.data.StringDict;
import processing.data.StringList;


/**
 * A class to hold information about a Contribution that can be downloaded.
 */
public class AvailableContribution extends Contribution {
  protected final ContributionType type;  // Library, tool, etc.
  protected final String link;  // Direct link to download the file


  public AvailableContribution(ContributionType type, StringDict params) {
    this.type = type;
    this.link = params.get("download");

    categories = parseCategories(params);
    imports = parseImports(params);
    name = params.get("name");
    // formerly authorList (but not a list, just free text)
    authors = params.get("authors");
    url = params.get("url");
    sentence = params.get("sentence");
    paragraph = params.get("paragraph");

    String versionStr = params.get("version");
    if (versionStr != null) {
      version = PApplet.parseInt(versionStr, 0);
    }

    setPrettyVersion(params.get("prettyVersion"));

    String lastUpdatedStr = params.get("lastUpdated");
    if (lastUpdatedStr != null) {
      try {
        lastUpdated =  Long.parseLong(lastUpdatedStr);
      } catch (NumberFormatException e) {
        lastUpdated = 0;
      }
    }
    String minRev = params.get("minRevision");
    if (minRev != null) {
      minRevision = PApplet.parseInt(minRev, 0);
    }

    String maxRev = params.get("maxRevision");
    if (maxRev != null) {
      maxRevision = PApplet.parseInt(maxRev, 0);
    }
  }


  static ContributionType matchContribType(String path) {
    String filename = path.substring(path.lastIndexOf('/') + 1);
    for (ContributionType type : ContributionType.values()) {
      if (filename.equals(type.getPropertiesName())) {
        return type;
      }
    }
    return null;
  }


  static public LocalContribution install(Base base, File contribArchive) throws IOException {
    AvailableContribution ac = null;

    ZipFile zf = new ZipFile(contribArchive);
    Enumeration entries = zf.entries();
    while (entries.hasMoreElements()) {
      ZipEntry entry = (ZipEntry) entries.nextElement();
      String name = entry.getName();
      if (name.endsWith(".properties")) {
        ContributionType type = matchContribType(name);
        if (type != null) {
          StringDict params = new StringDict();
          String[] lines = PApplet.loadStrings(zf.getInputStream(entry));
          if (lines != null) {
            for (String line : lines) {
              if (!line.startsWith("#")) {
                int equals = line.indexOf('=');
                if (equals != -1) {
                  String key = line.substring(0, equals).trim();
                  String value = line.substring(equals + 1).trim();
                  params.set(key, value);
                }
              }
            }
            ac = new AvailableContribution(type, params);
            break;  // found, let's get outta here

          } else {
            System.err.println("Could parse properties from " + name);
          }
        } else {
          System.err.println("Could not find a matching .properties file");
        }
      }
    }
    zf.close();

    if (ac != null) {
      return ac.install(base, contribArchive, false, null);
    }
    return null;
  }


  /**
   * @param contribArchive
   *          a zip file containing the library to install
   * @param confirmReplace
   *          true to open a dialog asking the user to confirm removing/moving
   *          the library when a library by the same name already exists
   * @param status
   *          the StatusPanel. Pass null if this function is called for an
   *          install-on-startup
   */
  public LocalContribution install(Base base, File contribArchive,
                                   boolean confirmReplace, StatusPanel status) {
    // Unzip the file into the modes, tools, or libraries folder inside the
    // sketchbook. Unzipping to /tmp is problematic because it may be on
    // another file system, so move/rename operations will break.
    File tempFolder;

    try {
      tempFolder = type.createTempFolder();
    } catch (IOException e) {
      if (status != null)
        status.setErrorMessage(Language.text("contrib.errors.temporary_directory"));
      return null;
    }
    Util.unzip(contribArchive, tempFolder);

    LocalContribution installedContrib = null;
    // Find the first legitimate folder in what we just unzipped
    File contribFolder = type.findCandidate(tempFolder);
    if (contribFolder == null) {
      final String err = Language.interpolate("contrib.errors.no_contribution_found", type);
      if (status != null) {
        status.setErrorMessage(err);
      } else {
        System.err.println(err);
      }
    } else {
      File propFile = new File(contribFolder, type + ".properties");
      if (!propFile.exists()) {
        status.setErrorMessage("This contribution is missing " +
                               propFile.getName() +
                               ", please contact the author for a fix.");

      } else {
        if (rewritePropertiesFile(propFile)) {
          // contribFolder now has a legit contribution, load it to get info.
          LocalContribution newContrib = type.load(base, contribFolder);

          // get info we need to delete the newContrib folder later
          File newContribFolder = newContrib.getFolder();

          // Check to make sure nothing has the same name already,
          // backup old if needed, then move things into place and reload.
          installedContrib =
            newContrib.copyAndLoad(base, confirmReplace, status);

          // Unlock all the jars if it is a mode or tool
          if (newContrib.getType() == ContributionType.MODE) {
            ((ModeContribution) newContrib).clearClassLoader(base);

          } else if (newContrib.getType() == ContributionType.TOOL) {
            ((ToolContribution) newContrib).clearClassLoader();
          }

          // Delete the newContrib, do a garbage collection, hope and pray
          // that Java will unlock the temp folder on Windows now
          //noinspection UnusedAssignment
          newContrib = null;
          System.gc();

          if (Platform.isWindows()) {
            // we'll even give it a second to finish up,
            // because file ops are just that flaky on Windows.
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }

          // delete the contrib folder inside the libraryXXXXXXtmp folder
          //Util.removeDir(newContribFolder, false);
          try {
            Platform.deleteFile(newContribFolder);
          } catch (IOException e) {
            e.printStackTrace();
          }
        } else {
          if (status != null) {
            status.setErrorMessage(Language.text("contrib.errors.overwriting_properties"));
          }
        }
      }
    }

    // Remove any remaining boogers
    if (tempFolder.exists()) {
      //Util.removeDir(tempFolder, false);
      try {
        Platform.deleteFile(tempFolder);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    return installedContrib;
  }


  public boolean isInstalled() {
    return false;
  }


  public ContributionType getType() {
    return type;
  }


  /**
   * We overwrite those fields that aren't proper in the properties file with
   * the curated version from the Processing site. This ensures that things have
   * been cleaned up (for instance, that the "sentence" is really a sentence)
   * and that bad data from the contrib's .properties file doesn't break the
   * manager. However, it also ensures that valid fields in the properties file
   * aren't overwritten, since the properties file may be more recent than the
   * contributions.txt file.
   */
  private boolean rewritePropertiesFile(File propFile) {
    try {
      StringDict properties = Util.readSettings(propFile);

      String name = properties.get("name");
      if (name == null || name.isEmpty()) {
        name = getName();
      }

      String category;
      StringList categoryList = parseCategories(properties);
      if (categoryList.size() == 1 &&
          categoryList.get(0).equals(UNKNOWN_CATEGORY)) {
        category = getCategoryStr();
      } else {
        category = categoryList.join(",");
      }

      StringList importsList = parseImports(properties);

      String authors = properties.get(AUTHORS_PROPERTY);
//      if (authors == null) {
//        authors = properties.get("authorList");  // before 3.0a11
//      }
      if (authors == null || authors.isEmpty()) {
        authors = getAuthorList();
      }

      String url = properties.get("url");
      if (url == null || url.isEmpty()) {
        url = getUrl();
      }

      String sentence = properties.get("sentence");
      if (sentence == null || sentence.isEmpty()) {
        sentence = getSentence();
      }

      String paragraph = properties.get("paragraph");
      if (paragraph == null || paragraph.isEmpty()) {
        paragraph = getParagraph();
      }

      int version;
      try {
        version = Integer.parseInt(properties.get("version"));
      } catch (NumberFormatException e) {
        version = getVersion();
        System.err.println("The version number for “" + name + "” is not a number.");
        System.err.println("Please contact the author to fix it according to the guidelines.");
      }

      String prettyVersion = properties.get("prettyVersion");
      if (prettyVersion != null && prettyVersion.isEmpty()) {
        prettyVersion = null;
      }

      String compatibleContribsList = null;
      if (getType() == ContributionType.EXAMPLES) {
        compatibleContribsList = properties.get(MODES_PROPERTY);
      }

      long lastUpdated;
      try {
        lastUpdated = Long.parseLong(properties.get("lastUpdated"));
      } catch (NumberFormatException nfe) {
        lastUpdated = getLastUpdated();
      // Better comment these out till all contribs have a lastUpdated
//        System.err.println("The last updated date for the “" + name
//                           + "” contribution is not set properly.");
//        System.err
//          .println("Please contact the author to fix it according to the guidelines.");
      }

      int minRev;
      try {
        minRev = Integer.parseInt(properties.get("minRevision"));
      } catch (NumberFormatException e) {
        minRev = getMinRevision();
//        System.err.println("The minimum compatible revision for the “" + name
//          + "” contribution is not set properly. Assuming minimum revision 0.");
      }

      int maxRev;
      try {
        maxRev = Integer.parseInt(properties.get("maxRevision"));
      } catch (NumberFormatException e) {
        maxRev = getMaxRevision();
//        System.err.println("The maximum compatible revision for the “" + name
//                           + "” contribution is not set properly. Assuming maximum revision INF.");
      }

      if (propFile.delete() && propFile.createNewFile() && propFile.setWritable(true)) {
        PrintWriter writer = PApplet.createWriter(propFile);

        writer.println("name=" + name);
        writer.println("category=" + category);
        writer.println(AUTHORS_PROPERTY + "=" + authors);
        writer.println("url=" + url);
        writer.println("sentence=" + sentence);
        writer.println("paragraph=" + paragraph);
        writer.println("version=" + version);
        if (prettyVersion != null) {
          writer.println("prettyVersion=" + prettyVersion);
        }
        writer.println("lastUpdated=" + lastUpdated);
        writer.println("minRevision=" + minRev);
        writer.println("maxRevision=" + maxRev);
        if ((getType() == ContributionType.LIBRARY || getType() == ContributionType.MODE) && importsList != null) {
          writer.println("imports=" + importsList.join(","));
        }
        if (getType() == ContributionType.EXAMPLES) {
          if (compatibleContribsList != null) {
            writer.println(MODES_PROPERTY + "=" + compatibleContribsList);
          }
        }

        writer.flush();
        writer.close();
      }
      return true;

    } catch (IOException e) {
      e.printStackTrace();
    }
    return false;
  }
}
