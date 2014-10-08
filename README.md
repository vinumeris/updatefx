UpdateFX
--------

UpdateFX is a small, simple automatic online update framework for JavaFX applications. It was created for apps
developed by the Bitcoin community and has the following features:

* Chrome-style updates which are downloaded and applied in the background whilst your app is running. A JFX Task is
  provided so you can easily connect download progress to a ProgressIndicator. Once an update has been applied, you
  can offer the user a simple "restart now" button.
* Each new version of the app is stored to the users home directory, thus administrator privileges are not needed
  to perform an update.
* Updates are distributed as binary deltas against the app JAR file.
* The update metadata file (index) is signed using Bitcoin compatible keys, thus the same infrastructure used to
  protect Bitcoin keys can be reused to protect app signing keys.
* Threshold signing i.e. you can require at least 3 of 5 public keys to sign an update index for it to be accepted.
* Ability for the user to pin themselves to a particular version, so they can downgrade or ignore further updates.
* Designed for usage with the JavaPackager tool introduced in Java 8.

Some things it does NOT provide include:

* UI specific code for showing release notes, download progress, etc. You are expected to integrate such things into
  your app in whatever style makes sense for your situation.
* Ability to update the bundled JVM.
* Ability to check for updates without applying them.

Some features it might provide in future could be:

* Ability for the user to downgrade to previous versions if new versions turn out to be problematic.
* Beta/alpha channels.
* Experiments.
* Ability to use a TREZOR to store the signing keys.

UpdateFX is [Apache 2.0 licensed](http://www.apache.org/licenses/LICENSE-2.0.html) which essentially means you can use it in proprietary projects if you want.

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

At this point, you should be able to drop a new version of your app into your apps data directory called "2.jar" and
running the original app should actually invoke the new version.

How to use: Step 2
------------------

You will need to specify a set of Bitcoin secp256k1 public keys used for signing updates. This is NOT the same as the
usual Java JAR signing infrastructure. Don't worry about this if you don't already have such a key, a tool called
UFXPrepare will create one for you later. For now, just create a `static final List<ECPoint>` (ECPoint
comes from Bouncy Castle) and use `Crypto.decode()` with an empty argument list. We'll add our key here later.

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
the user if at all possible. When clicked, offer to restart the app, which you can do by calling `UpdateFX.restartApp`.

How to use: step 3
------------------

Let's assume you have shipped the first version of your app using javapackager. You need to populate the directory
on your server with updates. To do this, you use a tool called UFXPrepare. UFXPrepare calculates binary deltas between
the JARs for each version of your app, then generates an index file that contains the URLs where the update files can be
found and their hashes, then signs it. The resulting directory can be uploaded to any HTTP server. Note that the
index file format has the ability to specify multiple URLs to store the actual updates, thus you can spread the files
around insecure mirrors and rely on the signing of the index file itself for security, although SSL is still recommended.
Amazon S3 is a good place to stick update deltas (it's free for reasonable amounts of usage).

Create a directory where you will work, and then create a subdirectory called builds. The first (shipping) version of
your app should be called 1.jar and placed in this directory. The next version of your app should be called 2.jar and
also placed in the builds directory (and so on).

Then run

java -jar updatefx-app-1.0-SNAPSHOT.jar --url=http://localhost:8000 /path/to/working/dir

It will create a new subdirectory parallel to "builds" called "site" which contains a 2.jar.bpatch file. This file
is a binary delta that applies against 1.jar. It will also create a file called "wallet" in the working directory if
one does not exist: this is a file that contains your signing key (in fact it's a bitcoinj format Bitcoin wallet). Keep
it safe! The URL parameter is where the *updates* will be served from, not where the index file will be served from.
For example this can be an Amazon S3 bucket.

The contents of the site directory can now be published at the URL you hard-coded into your app.