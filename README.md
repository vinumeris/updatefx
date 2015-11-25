Build status: [![Build Status](https://travis-ci.org/vinumeris/updatefx.png?branch=master)](https://travis-ci.org/vinumeris/updatefx)

UpdateFX
--------

UpdateFX is a small, simple automatic online update framework for JavaFX applications. It has the following features:

* Chrome-style updates which are downloaded and applied in the background whilst your app is running. A JFX Task is
  provided so you can easily connect download progress to a ProgressIndicator. Once an update has been applied, you
  can offer the user a simple "restart now" button. The way the update process looks in your UI is entirely up to you.
  It can be totally silent, very loud and noticeable, or anything in between.
* Each new version of the app is stored to the apps private data directory in the users home area, thus administrator 
  privileges are not needed to perform an update. Example: ~/Library/Application Support on a Mac, 
  C:\Users\USERNAME\AppData\Roaming\AppName on Windows, ~/.local/share/appname on Linux.
* Updates are distributed as binary deltas against timestamp-normalised, decompressed JAR files: they are compact.
* Updates can have titles, descriptions and other metadata that you can optionally display in your UI.
* Ability for the user to pin themselves to a particular version, so they can downgrade or ignore further updates.
* Designed for usage with the JavaPackager tool introduced in Java 8.
* Built for security sensitive apps (originally, apps that work with Bitcoin):
  * The update metadata file (index) is signed using elliptic curve keys on the secp256k1 curve, thus the same
    infrastructure used to protect Bitcoin keys can be reused to protect app signing keys.
  * Threshold signing: you can require at least N of M public keys to sign an update index for it to be accepted (e.g. 2 of 2 or 3 of 5)
  * Ability to use a [TREZOR](https://www.bitcointrezor.com/) to store the signing keys.

Some things it does NOT provide include:

* UI specific code for showing release notes, download progress, etc. You are expected to integrate such things into
  your app in whatever style makes sense for your situation. You can however reuse UI code from Lighthouse, an app
  that uses UpdateFX.
* Ability to update the bundled JVM.
* Ability to check for updates without applying them. We might do this in future.

Some features it might provide in future could be:

* Beta/alpha channels.
* Experiments.

UpdateFX is [Apache 2.0 licensed](http://www.apache.org/licenses/LICENSE-2.0.html) which means you can use it in
proprietary projects if you want. There may in future be a "pro" version that has additional functionality, for a price.
If you would like commercial support for UpdateFX, please email [Vinumeris GmbH](mailto:contact@vinumeris.com).

Maven coordinates
-----------------

| Group ID            | Artifact ID | Version |
| :-----------------: | :---------: | :-----: |
| com.vinumeris       | updatefx    | 1.5     |

How to use: Step 1
------------------

An example app is provided in the examples module. You can refer to this if you get stuck.

Firstly get your app into the right form. It must be a single JAR (use the Maven Shade plugin to do this) and it must
be deployed via native installers using the javapackager tool included in Java 8.

The application's startup sequence must be modified as follows:

1. Your static main method must call the `UpdateFX.bootstrap` method. Any code that runs before this point may have its
   results thrown away if a new version of the app has been downloaded, so you shouldn't do anything important here.
   The example app just sets up logging and potentially creates an app directory in a platform specific place
   for storing user data. We'll put updates here too. The AppDirectory class helps with setting this up. This is the
   only part of your app you cannot update so keep it small!
2. Your main class must implement a new `realMain(String[])` method. This is invoked by the bootstrap method
   and is the new entry point of your app: when new versions are in use, this is the earliest point at which they
   get control. All this should do is call launch as normal for a JavaFX app.
3. In order to work around a bug in JavaFX 8u25 and below, you must include the following code as the first line of your
   `realMain` method:

   `Thread.currentThread().setContextClassLoader(ExampleApp.class.getClassLoader());`

   where obviously ExampleApp is replaced with the name of your main app class.

At this point, you should be able to drop a new version of your app into your apps data directory called "2.jar" and
running the original app should actually invoke the new version.

How to use: Step 2
------------------

You will need to specify a set of secp256k1 elliptic curve public keys used for signing updates. This is NOT the same as
the usual Java JAR signing infrastructure. Don't worry about this if you don't already have such a key, a tool called
UFXPrepare will create one for you later. For now, just create a `static final List<ECPoint>` (ECPoint
comes from Bouncy Castle) and use `Crypto.decode()` with an empty argument list. We'll add our key here in a bit.

Choose a base URL from which updates will be served. For local testing, you can just use a local web server. If you
have Python installed (MacOS X has this out of the box), you can just create a directory and run

  `python -m SimpleHTTPServer 8000`

to serve the current directory over HTTP on port 8000.

Shortly after your app starts up, create an Updater object. This is a `Task<UpdateSummary>` that will download a file
called "index" from the specified base URL, check the signatures on it, figure out what updates need to be applied
to get the user to the latest version, download them, apply the deltas and drop a new complete JAR into the user-specific
data directory you specify. Finally it results in an `UpdateSummary` object which tells you what the newest version
that was downloaded is, so you can decide what to do.

The Updater object wants several arguments in its constructor, but they are all straightforward:

* The base URL. For testing use localhost. Once satisfied everything is working, change this to be a directory on
  your website.
* A string that'll be sent to your website as the HTTP User-Agent. Set this to something that includes the current
  version so you can see what your users are running and how fast they upgrade.
* The current version number itself. A "version number" in UpdateFX is just a monotonically increasing integer. It has
  no special structure and you are not allowed to have, e.g. fractional versions. It does not need to correspond to
  the version number you show users, but higher numbers are always newer versions.
* The directory to store updates and downloaded patches in.
* The path of the JAR of the current version. The best way to find this is use `UpdateFX.findCodePath(ExampleApp.class)`
  where ExampleApp is obviously replaced with the name of your apps main/startup class.
* The list of pubkeys we created using Crypto.decode
* How many of those pubkeys need to sign for an index to be accepted. For example if you specify 2 here then there must
  be at least two keys in the list and both of them must have provided valid signatures. If you specify 1 then either
  key can be used.

As with any Task you can bind the progress property. Note that other aspects of a task like current title etc are not
used. The progress property starts as indeterminate and only starts tracking real progress if patches start being
downloaded, thus, you can optionally decide to only show some kind of progress indicator if the amount of progress
is changed. Finally the onSucceeded event can be used to run code when an update has been applied. The most useful
thing to do here is place a small/subtle indicator that an update is available somewhere in your UI. Don't interrupt
the user if possible, as they may be getting work done. When clicked, offer to restart the app, which you can do
by calling `UpdateFX.restartApp`.

How to use: step 3
------------------

The final step is to use the UFXPrepare program. This does several things:

1. It modifies JAR files to make them more amenable to delta encoded updates. Specifically it decompresses them and
   zeros out the timestamps in the JAR/ZIP metadata.
2. It creates and loads a "wallet" file that contains your signing keys. You will be prompted for a password.
3. It reads the different JARs corresponding to different versions of your app and calculates binary deltas between them,
   then signs a binary index file that contains URLs of the binary deltas.
4. It extracts update description files from each JAR and includes the text into the index.

Let's assume you have built a bundled jar of the first version of your app.

Create a directory where you will work, and then create a subdirectory called builds. The first version of
your app should be called 1.jar and placed in this directory. The next version of your app should be called 2.jar and
also placed in the builds directory (and so on).

Then run

java -jar updatefx-app-1.0.jar --url=http://localhost:8000 /path/to/working/dir

This does two things:

1. It will create a new subdirectory parallel to "builds" called "site" which contains a 2.jar.bpatch file. This file
   is a binary delta that applies against 1.jar. It will also create a file called "wallet" in the working directory if
   one does not exist: this is a file that contains your signing key (in fact it's a bitcoinj format Bitcoin wallet). Keep
   it safe! The URL parameter is where the *updates* will be served from, not where the index file will be served from.
   For example this can be an Amazon S3 bucket.
2. It will create a subdirectory of builds called processed, with modified copies of the JAR files that you have placed
   inside the builds directory. The processed JARs are decompressed and have zerod timestamps. *You must distribute
   the processed version in your installers!*

The contents of the site directory can now be published at the URL you hard-coded into your app, and the 1.jar file
that is sitting in the builds/processed directory can now be fed to javapackager to produce the final native packages and
installers.

If the JAR contains a file called `update-description.txt` then the first line will be used as the title of the update,
and the rest will be used as the description. These fields are exposed via a list in the UpdateSummary object. You can,
for example, use this data to populate a window that lets the user pick which version they'd like to downgrade/upgrade
to.

User interface design
---------------------

UpdateFX does not impose any particular UI design on you. However, you probably do want some kind of user interface :)

[Lighthouse](https://github.com/vinumeris/lighthouse) is the application that drove the need for UpdateFX and it's also
Apache 2.0 licensed, so you can take code from it if you want to. Lighthouse implements the following user interface
for updates:

1. A dark notification bar springs up from the bottom of the main window when updates are being downloaded. It contains
   a progress bar that grows as the updates are fetched (multiple updates being applied at once look identical to a
   single update). Once the updates are done, the progress bar is replaced with a small restart button. The
   [NotificationBarPane class](https://github.com/vinumeris/lighthouse/blob/master/client/src/main/java/lighthouse/controls/NotificationBarPane.java)
   is general and can be used for any bottom-bar based notifications.
2. The [UpdateFXWindow class](https://github.com/vinumeris/lighthouse/blob/master/client/src/main/java/lighthouse/subwindows/UpdateFXWindow.java)
   implements a simple UI for showing the user a list of all updates that have been applied,
   along with a button that lets the user change which version they are running - for example, to downgrade in case of
   regressions. The window also lets the user pin themselves to a particular version or whatever the latest version is,
   so users can ensure they don't get accidentally upgraded to a broken version the day before an important business
   meeting.

   The code in this class probably won't be copy/pasteable directly into your app as it expects various other utility
   functions and CSS to be present, but you can use it as a starting point if you'd like the same features.

