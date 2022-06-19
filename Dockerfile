FROM redhat/ubi8 as builder
COPY get_it.sh ./
RUN \
  dnf install -y java-17-openjdk-devel git && \
  ./get_it.sh \
  ./gradlew

#FROM redhat/ubi8
#RUN \
#  groupadd -g 1001 launchserver && \
#  useradd -mu 1001 -g launchserver launchserver && \
#  chmod 777 /tmp && \
#  dnf install -y java-17-openjdk 
#COPY --chown=1001:1001 docker-entrypoint.sh /
#WORKDIR /app
#COPY config/ ./
#
#RUN  \
#  curl -o multipaper.jar ${slave_jar_url} && \
#  mkdir /pv && \
#  chown 1001:1001 -R ./ /pv/
#USER launchserver
#EXPOSE 25565
#ENTRYPOINT ["/docker-entrypoint.sh"] */

