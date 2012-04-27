### How to Build TME

#### CentOS

Tested on CentOS 5 and 6

Prerequisites packages:

* Sun JDK (jdk-1.6.0_21+)
* Apache Ant (1.8.2+)
* GNU g++ (gcc-c++)
* CMake (cmake)
* Boost (boost-devel)
* rpmbuild (rpm-build)
* xml-commons-apis (xml-commons-apis)

Make sure that the binary executable of ant is included in the search path, and it uses the Sun JDK to build.

Simply run `make rpm` in the top directory, and all the RPM packages of TME will be generated to `output/artifacts`

#### Ubuntu

Tested on Ubuntu 10.04 LTS, using OpenJDK should work.

To prepare the build environment:

1. `apt-get install git-core`
2. `apt-get install openjdk-6-jdk`
3. `apt-get install ant1.8`
4. `apt-get install g++`
5. `apt-get install cmake`
6. `apt-get install libboost-program-options1.40-dev`

TME web portal only supports Ruby 1.9.2+, and Ubuntu 10.04 only ships Ruby 1.9.1

You have to follow this step to use RVM to install Ruby 1.9.2:

1. `aptitude install build-essential libssl-dev libreadline5 libreadline5-dev zlib1g zlib1g-dev curl`
2. `curl https://raw.github.com/wayneeseguin/rvm/master/binscripts/rvm-installer | bash -s stable`
3. `source /etc/profile.d/rvm.sh`
4. `rvm install 1.9.2 ; rvm default 1.9.2`
5. `gem install bundler`

#### Mac OS X (Lion)

(draft)

To prepare the build environment:

1. Install [Command Line Tools for Xcode] from [Downloads for Apple Developers](https://developer.apple.com/downloads/) (free Apple ID required). See [Xcode, GCC, and Homebrew ](http://kennethreitz.com/xcode-gcc-and-homebrew.html).
2. Install [Homebrew](http://mxcl.github.com/homebrew/): `/usr/bin/ruby -e "$(/usr/bin/curl -fksSL https://raw.github.com/mxcl/homebrew/master/Library/Contributions/install_homebrew.rb)"`
3. Install [brew-gem](https://github.com/josh/brew-gem): `brew install brew-gem`
4. Install [Bundler](http://gembundler.com/): `brew gem bundle`

