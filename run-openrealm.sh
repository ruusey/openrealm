#IN TERMINAL
curl -s "https://get.sdkman.io" | bash

#Restart Terminal after this step^
sdk list java
sdk install java 17.0.4.1-tem

java -version
#Should print 17.X.X.X^
java -jar ./openrealm.jar 127.0.0.1
