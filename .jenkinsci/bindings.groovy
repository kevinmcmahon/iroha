#!/usr/bin/env groovy

def doJavaBindings(os, packageName, buildType=Release) {
  def currentPath = sh(script: "pwd", returnStdout: true).trim()
  def commit = env.GIT_COMMIT
  def artifactsPath = sprintf('%1$s/java-bindings-%2$s-%3$s-%4$s-%5$s.zip',
    [currentPath, buildType, os, sh(script: 'date "+%Y%m%d"', returnStdout: true).trim(), commit.substring(0,6)])
  def cmakeOptions = ""
  if (os == 'windows') {
    sh "mkdir -p /tmp/${commit}/bindings-artifact"
    cmakeOptions = '-DCMAKE_TOOLCHAIN_FILE=/c/Users/Administrator/Downloads/vcpkg-master/vcpkg-master/scripts/buildsystems/vcpkg.cmake -G "NMake Makefiles"'
  }
  if (os == 'linux') {
    // do not use preinstalled libed25519
    sh "rm -rf /usr/local/include/ed25519*; unlink /usr/local/lib/libed25519.so; rm -f /usr/local/lib/libed25519.so.1.2.2"
  }
  sh """
    cmake \
      -Hshared_model \
      -Bbuild \
      -DCMAKE_BUILD_TYPE=$buildType \
      -DSWIG_JAVA=ON \
      -DSWIG_JAVA_PKG="$packageName" \
      ${cmakeOptions}
  """
  def parallelismParam = (os == 'windows') ? '' : "-j${params.PARALLELISM}"
  sh "cmake --build build --target irohajava -- ${parallelismParam}"
  // TODO 29.05.18 @bakhtin Java tests never finishes on Windows Server 2016. IR-1380
  sh "pushd build/bindings; \
      zip -r $artifactsPath *.dll *.lib *.manifest *.exp libirohajava.so \$(echo ${packageName} | cut -d '.' -f1); \
      popd"
  if (os == 'windows') {
    sh "cp $artifactsPath /tmp/${commit}/bindings-artifact"
  }
  else {
    sh "cp $artifactsPath /tmp/bindings-artifact"
  }
  return artifactsPath
}

def doPythonBindings(os, buildType=Release) {
  def currentPath = sh(script: "pwd", returnStdout: true).trim()
  def commit = env.GIT_COMMIT
  def supportPython2 = "OFF"
  def artifactsPath = sprintf('%1$s/python-bindings-%2$s-%3$s-%4$s-%5$s-%6$s.zip',
    [currentPath, env.PBVersion, buildType, os, sh(script: 'date "+%Y%m%d"', returnStdout: true).trim(), commit.substring(0,6)])
  def cmakeOptions = ""
  if (os == 'windows') {
    sh "mkdir -p /tmp/${commit}/bindings-artifact"
    cmakeOptions = '-DCMAKE_TOOLCHAIN_FILE=/c/Users/Administrator/Downloads/vcpkg-master/vcpkg-master/scripts/buildsystems/vcpkg.cmake -G "NMake Makefiles"'
  }
  if (os == 'mac') {
    sh "mkdir -p /tmp/${commit}/bindings-artifact"
    cmakeOptions = "-DPYTHON_INCLUDE_DIR=/Users/jenkins/.pyenv/versions/3.5.5/include/python3.5m/ -DPYTHON_LIBRARY=/Users/jenkins/.pyenv/versions/3.5.5/lib/libpython3.5m.a -DPYTHON_EXECUTABLE=/Users/jenkins/.pyenv/versions/3.5.5/bin/python3.5"
  }
  if (os == 'linux') {
    // do not use preinstalled libed25519
    cmakeOptions = "-DPYTHON_LIBRARY=/usr/lib/x86_64-linux-gnu/libpython3.5m.a -DPYTHON_INCLUDE_DIR=/usr/include/python3.5m/ -DPYTHON_EXECUTABLE=/usr/bin/python3.5"
    sh "rm -rf /usr/local/include/ed25519*; unlink /usr/local/lib/libed25519.so; rm -f /usr/local/lib/libed25519.so.1.2.2"
  }
  if (env.PBVersion == "python2") { supportPython2 = "ON" }
  sh """
    cmake \
      -Hshared_model \
      -Bbuild \
      -DCMAKE_BUILD_TYPE=${buildType} \
      -DSWIG_PYTHON=ON \
      -DSUPPORT_PYTHON2=${supportPython2} \
      ${cmakeOptions}
  """
  def parallelismParam = (os == 'windows') ? '' : "-j${params.PARALLELISM}"
  sh "cmake --build build --target irohapy -- ${parallelismParam}"
  sh "cmake --build build --target python_tests"
  sh "cd build; ctest -R python --output-on-failure"
  if (os == 'mac' || os == 'linux') {
    sh """
      protoc --proto_path=schema \
        --python_out=build/bindings \
        block.proto primitive.proto commands.proto queries.proto responses.proto endpoint.proto
      python -m grpc_tools.protoc --proto_path=schema --python_out=build/bindings \
        --grpc_python_out=build/bindings endpoint.proto yac.proto ordering.proto loader.proto
    """
  }
  if (os == 'windows') {
    sh """
      protoc --proto_path=schema \
        --proto_path=/c/Users/Administrator/Downloads/vcpkg-master/vcpkg-master/buildtrees/protobuf/src/protobuf-3.5.1-win32/include \
        --python_out=build/bindings \
        block.proto primitive.proto commands.proto queries.proto responses.proto endpoint.proto
    """
    sh """
      python -m grpc_tools.protoc \
        --proto_path=/c/Users/Administrator/Downloads/vcpkg-master/vcpkg-master/buildtrees/protobuf/src/protobuf-3.5.1-win32/include \
        --proto_path=schema --python_out=build/bindings --grpc_python_out=build/bindings \
        endpoint.proto yac.proto ordering.proto loader.proto
    """
  }
  sh " zip -j ${artifactsPath} build/bindings/*.{py,dll,so,pyd,lib,dll,exp,manifest} || true"
  if (os == 'windows' || os == 'mac') {
    sh "cp ${artifactsPath} /tmp/${commit}/bindings-artifact"
  }
  else {
    sh "cp ${artifactsPath} /tmp/bindings-artifact"
  }
  doPythonWheels(os, buildType);
  return artifactsPath
}

def doAndroidBindings(abiVersion) {
  def currentPath = sh(script: "pwd", returnStdout: true).trim()
  def commit = env.GIT_COMMIT
  def artifactsPath = sprintf('%1$s/android-bindings-%2$s-%3$s-%4$s-%5$s-%6$s.zip',
    [currentPath, "\$PLATFORM", abiVersion, "\$BUILD_TYPE_A", sh(script: 'date "+%Y%m%d"', returnStdout: true).trim(), commit.substring(0,6)])
  sh """
    (cd /iroha; git init; git remote add origin https://github.com/hyperledger/iroha.git; \
    git fetch origin ${GIT_COMMIT}; git checkout FETCH_HEAD)
  """
  sh """
    . /entrypoint.sh; \
    sed -i.bak "s~find_package(JNI REQUIRED)~SET(CMAKE_SWIG_FLAGS \\\${CMAKE_SWIG_FLAGS} -package \${PACKAGE})~" /iroha/shared_model/bindings/CMakeLists.txt; \
    # TODO: might not be needed in the future
    sed -i.bak "/target_include_directories(\\\${SWIG_MODULE_irohajava_REAL_NAME} PUBLIC/,+3d" /iroha/shared_model/bindings/CMakeLists.txt; \
    sed -i.bak "s~swig_link_libraries(irohajava~swig_link_libraries(irohajava \"/protobuf/.build/lib\${PROTOBUF_LIB_NAME}.a\" \"\${NDK_PATH}/platforms/android-$abiVersion/\${ARCH}/usr/\${LIBP}/liblog.so\"~" /iroha/shared_model/bindings/CMakeLists.txt; \
    sed -i.bak "s~find_library(protobuf_LIBRARY protobuf)~find_library(protobuf_LIBRARY \${PROTOBUF_LIB_NAME})~" /iroha/cmake/Modules/Findprotobuf.cmake; \
    sed -i.bak "s~find_program(protoc_EXECUTABLE protoc~set(protoc_EXECUTABLE \"/protobuf/host_build/protoc\"~" /iroha/cmake/Modules/Findprotobuf.cmake; \
    cmake -H/iroha/shared_model -B/iroha/shared_model/build -DCMAKE_SYSTEM_NAME=Android -DCMAKE_SYSTEM_VERSION=$abiVersion -DCMAKE_ANDROID_ARCH_ABI=\$PLATFORM \
      -DANDROID_NDK=\$NDK_PATH -DCMAKE_ANDROID_STL_TYPE=c++_static -DCMAKE_BUILD_TYPE=\$BUILD_TYPE_A -DTESTING=OFF \
      -DSWIG_JAVA=ON -DCMAKE_PREFIX_PATH=\$DEPS_DIR
    """
  sh "cmake --build /iroha/shared_model/build --target irohajava -- -j${params.PARALLELISM}"
  sh "zip -j $artifactsPath /iroha/shared_model/build/bindings/*.java /iroha/shared_model/build/bindings/libirohajava.so"
  sh "cp $artifactsPath /tmp/bindings-artifact"
  return artifactsPath
}

def doPythonWheels(os, buildType) {
  def version;
  def repo;
  def envs
  if (os == 'linux') { envs = (env.PBVersion == "python2") ? "pip" : "pip3" }
  else if (os == 'mac') { envs = (env.PBVersion == "python2") ? "2.7.15" : "3.5.5" }
  else if (os == 'windows') { envs = (env.PBVersion == "python2") ? "py2.7" : "py3.5" }

  version = sh(script: 'git describe --tags \$(git rev-list --tags --max-count=1)', returnStdout: true).trim()
  version += ".dev" + env.BUILD_NUMBER

  repo = 'develop'
  // if (env.GIT_TAG_NAME != null || env.GIT_LOCAL_BRANCH == "master") {
  //   version = sh(script: 'git describe --tags \$(git rev-list --tags --max-count=1)', returnStdout: true).trim()
  //   repo = "release"
  // }
  // else {    
  //   version = sh(script: 'git describe --tags \$(git rev-list --tags --max-count=1)', returnStdout: true).trim()
  //   version += "dev"
  //   repo = "develop"
  //   if (params.nightly == true) {
  //     version += "-nightly"
  //     repo += "-nightly"
  //   }
  //   version +="-${env.GIT_COMMIT.substring(0,8)}"
  // }
  sh """
    mkdir -p wheels/iroha; \
    cp build/bindings/*.{py,dll,so,pyd,lib,dll,exp,mainfest} wheels/iroha &> /dev/null || true; \
    cp .jenkinsci/python_bindings/files/setup.{py,cfg} wheels &> /dev/null || true; \
    cp .jenkinsci/python_bindings/files/__init__.py wheels/iroha/; \
    sed -i.bak 's/{{ PYPI_VERSION }}/${version}/g' wheels/setup.py; \
    modules=(\$(find wheels/iroha -type f -not -name '__init__.py' | sed 's/wheels\\/iroha\\///g' | grep '\\.py\$' | sed -e 's/\\..*\$//')); \
    for f in wheels/iroha/*.py; do for m in "\${modules[@]}"; do sed -i.bak "s/import \$m/from . import \$m/g" \$f; done; done;
  """
  if (os == 'mac' || os == 'linux') {
    sh """
      pyenv global ${envs}; \
      pip wheel --no-deps wheels/; \
      pyenv global 3.5.5; \
    """
  }
  else if (os == 'windows') {
    sh """
      source activate ${envs}; \
      pip wheel --no-deps wheels/; \
      source deactivate;
    """
  }

  if (env.PBBuildType == "Release")
    withCredentials([usernamePassword(credentialsId: 'ci_nexus', passwordVariable: 'CI_NEXUS_PASSWORD', usernameVariable: 'CI_NEXUS_USERNAME')]) {
        sh "twine upload --skip-existing -u ${CI_NEXUS_USERNAME} -p ${CI_NEXUS_PASSWORD} --repository-url https://nexus.soramitsu.co.jp/repository/pypi-${repo}/ *.whl"
    }
}
return this
