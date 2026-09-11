FROM rust:1.89-bookworm AS build
WORKDIR /src
COPY services/engine-v2 ./services/engine-v2
WORKDIR /src/services/engine-v2
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10002 --home /nonexistent --shell /usr/sbin/nologin tukuengine
COPY --from=build /src/services/engine-v2/target/release/tuku-engine-v2 /usr/local/bin/tuku-engine-v2
USER tukuengine
EXPOSE 8088 2525 2143
ENTRYPOINT ["/usr/local/bin/tuku-engine-v2"]
