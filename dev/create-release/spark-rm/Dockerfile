#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Image for building Spark releases. Based on Ubuntu 18.04.
#
# Includes:
# * Java 8
# * Ivy
# * Python (2.7.15/3.6.7)
# * R-base/R-base-dev (3.6.1)
# * Ruby 2.3 build utilities

FROM ubuntu:18.04

# For apt to be noninteractive
ENV DEBIAN_FRONTEND noninteractive
ENV DEBCONF_NONINTERACTIVE_SEEN true

# These arguments are just for reuse and not really meant to be customized.
ARG APT_INSTALL="apt-get install --no-install-recommends -y"

ARG PIP_PKGS="sphinx==2.3.1 mkdocs==1.0.4 numpy==1.18.1"
ARG GEM_PKGS="jekyll:4.0.0 jekyll-redirect-from:0.16.0 rouge:3.15.0"

# Install extra needed repos and refresh.
# - CRAN repo
# - Ruby repo (for doc generation)
#
# This is all in a single "RUN" command so that if anything changes, "apt update" is run to fetch
# the most current package versions (instead of potentially using old versions cached by docker).
RUN apt-get clean && apt-get update && $APT_INSTALL gnupg ca-certificates && \
  echo 'deb https://cloud.r-project.org/bin/linux/ubuntu bionic-cran35/' >> /etc/apt/sources.list && \
  gpg --keyserver keyserver.ubuntu.com --recv-key E298A3A825C0D65DFD57CBB651716619E084DAB9 && \
  gpg -a --export E084DAB9 | apt-key add - && \
  apt-get clean && \
  rm -rf /var/lib/apt/lists/* && \
  apt-get clean && \
  apt-get update && \
  $APT_INSTALL software-properties-common && \
  apt-add-repository -y ppa:brightbox/ruby-ng && \
  apt-get update && \
  # Install openjdk 8.
  $APT_INSTALL openjdk-8-jdk && \
  update-alternatives --set java /usr/lib/jvm/java-8-openjdk-amd64/jre/bin/java && \
  # Install build / source control tools
  $APT_INSTALL curl wget git maven ivy subversion make gcc lsof libffi-dev \
    pandoc pandoc-citeproc libssl-dev libcurl4-openssl-dev libxml2-dev && \
  curl -sL https://deb.nodesource.com/setup_11.x | bash && \
  $APT_INSTALL nodejs && \
  # Install needed python packages. Use pip for installing packages (for consistency).
  $APT_INSTALL libpython3-dev python3-pip python3-setuptools && \
  # Change default python version to python3.
  update-alternatives --install /usr/bin/python python /usr/bin/python2.7 1 && \
  update-alternatives --install /usr/bin/python python /usr/bin/python3.6 2 && \
  update-alternatives --set python /usr/bin/python3.6 && \
  pip3 install $PIP_PKGS && \
  # Install R packages and dependencies used when building.
  # R depends on pandoc*, libssl (which are installed above).
  $APT_INSTALL r-base r-base-dev && \
  $APT_INSTALL texlive-latex-base texlive texlive-fonts-extra texinfo qpdf && \
  Rscript -e "install.packages(c('curl', 'xml2', 'httr', 'devtools', 'testthat', 'knitr', 'rmarkdown', 'roxygen2', 'e1071', 'survival'), repos='https://cloud.r-project.org/')" && \
  Rscript -e "devtools::install_github('jimhester/lintr')" && \
  # Install tools needed to build the documentation.
  $APT_INSTALL ruby2.5 ruby2.5-dev && \
  gem install --no-document $GEM_PKGS

WORKDIR /opt/spark-rm/output

ARG UID
RUN useradd -m -s /bin/bash -p spark-rm -u $UID spark-rm
USER spark-rm:spark-rm

ENTRYPOINT [ "/opt/spark-rm/do-release.sh" ]
