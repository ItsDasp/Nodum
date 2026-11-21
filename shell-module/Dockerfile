FROM alpine:3.21
RUN apk add --no-cache bash ca-certificates coreutils curl bind-tools jq git less nano util-linux \
    && addgroup -g 10001 -S nodum \
    && adduser -u 10001 -S -D -h /home/nodum -G nodum nodum \
    && mkdir -p /workspace \
    && chown -R nodum:nodum /home/nodum /workspace
ENV HOME=/home/nodum
WORKDIR /workspace
USER 10001:10001
CMD ["sleep", "infinity"]
