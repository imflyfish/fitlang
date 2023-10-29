
httpPrefix=$FIT_SERVER_HTTP_PREFIX
fitPath=$FIT_PATH

cd "$(dirname $0)/.."

#curl http://127.0.0.1:11111/_stop
curl http://127.0.0.1:11111/_shutdown

sleep 0.5

java -classpath lib/fitlang-0.6.6.jar:\
lib/hutool-all-5.8.21.jar:\
lib/SparseBitSet-1.2.jar:\
lib/netty-codec-socks-4.1.97.Final.jar:\
lib/netty-transport-native-epoll-4.1.97.Final-linux-aarch_64.jar:\
lib/aviator-5.3.3.jar:\
lib/jna-5.13.0.jar:\
lib/netty-codec-stomp-4.1.97.Final.jar:\
lib/netty-transport-native-epoll-4.1.97.Final-linux-x86_64.jar:\
lib/commons-codec-1.13.jar:\
lib/jna-platform-5.13.0.jar:\
lib/netty-codec-xml-4.1.97.Final.jar:\
lib/netty-transport-native-kqueue-4.1.97.Final-osx-aarch_64.jar:\
lib/commons-collections4-4.4.jar:\
lib/jxl-2.6.jar:\
lib/netty-common-4.1.97.Final.jar:\
lib/netty-transport-native-kqueue-4.1.97.Final-osx-x86_64.jar:\
lib/commons-compress-1.19.jar:\
lib/netty-all-4.1.97.Final.jar:\
lib/netty-handler-4.1.97.Final.jar:\
lib/netty-transport-native-unix-common-4.1.97.Final.jar:\
lib/commons-csv-1.8.jar:\
lib/netty-buffer-4.1.97.Final.jar:\
lib/netty-handler-proxy-4.1.97.Final.jar:\
lib/netty-transport-rxtx-4.1.97.Final.jar:\
lib/commons-io-2.11.0.jar:\
lib/netty-codec-4.1.97.Final.jar:\
lib/netty-handler-ssl-ocsp-4.1.97.Final.jar:\
lib/netty-transport-sctp-4.1.97.Final.jar:\
lib/commons-math3-3.6.1.jar:\
lib/netty-codec-dns-4.1.97.Final.jar:\
lib/netty-resolver-4.1.97.Final.jar:\
lib/netty-transport-udt-4.1.97.Final.jar:\
lib/curvesapi-1.06.jar:\
lib/netty-codec-haproxy-4.1.97.Final.jar:\
lib/netty-resolver-dns-4.1.97.Final.jar:\
lib/oshi-core-6.4.5.jar:\
lib/easyexcel-3.3.2.jar:\
lib/netty-codec-http-4.1.97.Final.jar:\
lib/netty-resolver-dns-classes-macos-4.1.97.Final.jar:\
lib/poi-4.1.2.jar:\
lib/easyexcel-core-3.3.2.jar:\
lib/netty-codec-http2-4.1.97.Final.jar:\
lib/netty-resolver-dns-native-macos-4.1.97.Final-osx-aarch_64.jar:\
lib/poi-ooxml-4.1.2.jar:\
lib/easyexcel-support-3.3.2.jar:\
lib/netty-codec-memcache-4.1.97.Final.jar:\
lib/netty-resolver-dns-native-macos-4.1.97.Final-osx-x86_64.jar:\
lib/poi-ooxml-schemas-4.1.2.jar:\
lib/ehcache-3.9.9.jar:\
lib/netty-codec-mqtt-4.1.97.Final.jar:\
lib/netty-transport-4.1.97.Final.jar:\
lib/fastjson2-2.0.32.jar:\
lib/netty-codec-redis-4.1.97.Final.jar:\
lib/netty-transport-classes-epoll-4.1.97.Final.jar:\
lib/slf4j-api-2.0.7.jar:\
lib/netty-codec-smtp-4.1.97.Final.jar:\
lib/netty-transport-classes-kqueue-4.1.97.Final.jar:\
lib/xmlbeans-3.1.0.jar\
 fit.server.FitServerMain server.fit $httpPrefix $fitPath&

