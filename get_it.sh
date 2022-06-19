#!/bin/sh
git clone https://github.com/MetaCringer/mcLauncher.git
cd Launcher
sed -i 's/git@github.com:/https:\/\/github.com\//' .gitmodules
git submodule sync
git submodule update --init --recursive
