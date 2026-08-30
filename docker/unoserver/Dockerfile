# Standalone unoserver image for Morphe PDF remote UNO mode.
# Pinned to unoserver 3.6 to match Morphe PDF's client (avoids 3.4/3.6 wire mismatch).

FROM ubuntu:noble@sha256:33ceb71981b602c1a7443a53469e4dba065f7503eab3078a2d7a57a2ab987517

ARG UNOSERVER_VERSION=3.6
# ~120 MB of CJK fonts — opt-in.
ARG INSTALL_CJK_FONTS=false

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    UNOSERVER_PORT=2003 \
    UNOSERVER_UNO_PORT=2002 \
    UNOSERVER_INTERFACE=0.0.0.0 \
    UNOSERVER_CONVERSION_TIMEOUT=1800 \
    UNOSERVER_RECYCLE_INTERVAL_SECONDS=0 \
    UNOSERVER_PROFILE_DIR=/var/lib/unoserver/profile

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      ca-certificates curl gnupg dirmngr tini; \
    # Add the LibreOffice Fresh PPA without add-apt-repository: that queries the
    # Launchpad API, which returns HTTP 500 to GitHub-hosted runner IPs (see
    # docker/base/Dockerfile for the same fix). Fetch the signing key from
    # keyserver.ubuntu.com with retries and write the sources entry directly.
    install -d -m 0755 /etc/apt/keyrings; \
    GNUPGHOME="$(mktemp -d)"; export GNUPGHOME; \
    for i in 1 2 3 4 5; do \
      gpg --no-default-keyring --keyring /etc/apt/keyrings/libreoffice-ppa.gpg \
          --keyserver hkps://keyserver.ubuntu.com \
          --recv-keys CDDE43BA4DE3F09D7CE7016673AEE920AD55F5A0 && break; \
      if [ "$i" = 5 ]; then exit 1; fi; sleep 10; \
    done; \
    . /etc/os-release; \
    echo "deb [signed-by=/etc/apt/keyrings/libreoffice-ppa.gpg] https://ppa.launchpadcontent.net/libreoffice/ppa/ubuntu $VERSION_CODENAME main" \
      > /etc/apt/sources.list.d/libreoffice-ppa.list; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      python3 python3-venv python3-uno python3-packaging \
      libreoffice-writer-nogui libreoffice-calc-nogui \
      libreoffice-impress-nogui libreoffice-draw-nogui \
      libreoffice-base-nogui libreoffice-java-common \
      xvfb x11-utils coreutils \
      fonts-dejavu fonts-liberation fonts-liberation2 \
      fonts-crosextra-caladea fonts-crosextra-carlito \
      fonts-noto-core fonts-noto-mono fonts-noto-color-emoji \
      hyphen-en-gb hyphen-en-us hyphen-fr hyphen-de hyphen-es hyphen-it \
      hyphen-pt-pt hyphen-pt-br hyphen-nl hyphen-pl hyphen-ru \
      fontconfig \
      libcairo2 libpango-1.0-0 libpangoft2-1.0-0 libgdk-pixbuf-2.0-0 \
      libfreetype6 libfontconfig1 libx11-6 libxt6 libxext6 libxrender1 \
      libxtst6 libxi6 libxinerama1 libxkbcommon0 libsm6 libice6 \
      libglib2.0-0; \
    if [ "${INSTALL_CJK_FONTS}" = "true" ]; then \
      apt-get install -y --no-install-recommends fonts-noto-cjk; \
    fi; \
    apt-get remove --purge -y gnupg dirmngr; \
    apt-get autoremove --purge -y; \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

# Noble's hyphen-* packages skip LibreOffice registration; wire it up by hand
# or the dicts never load.
RUN set -eu; \
    mkdir -p /usr/share/myspell/dicts /usr/share/myspell/infos/ooo; \
    for f in /usr/share/hyphen/hyph_*_*.dic; do \
      base=$(basename "$f" .dic); \
      locale=${base#hyph_}; \
      lang=${locale%_*}; \
      country=${locale#*_}; \
      ln -sfn "../../hyphen/${base}.dic" "/usr/share/myspell/dicts/${base}.dic"; \
      printf "HYPH %s %s %s\n" "$lang" "$country" "$base" \
        > "/usr/share/myspell/infos/ooo/hyphen-${lang}-${country}"; \
    done

COPY --chmod=0644 docker/unoserver/fonts.conf /etc/fonts/conf.d/100-stirling.conf
RUN fc-cache -f

# venv with --system-site-packages so it picks up distro python3-uno.
RUN python3 -m venv /opt/unoserver-venv --system-site-packages && \
    /opt/unoserver-venv/bin/pip install --no-cache-dir "unoserver==${UNOSERVER_VERSION}" && \
    ln -sf /opt/unoserver-venv/bin/unoserver  /usr/local/bin/unoserver && \
    ln -sf /opt/unoserver-venv/bin/unoconvert /usr/local/bin/unoconvert && \
    ln -sf /opt/unoserver-venv/bin/unoping    /usr/local/bin/unoping && \
    find /opt/unoserver-venv -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true

RUN rm -rf /usr/lib/libreoffice/share/gallery \
           /usr/lib/libreoffice/share/template \
           /usr/lib/libreoffice/share/wizards \
           /usr/lib/libreoffice/help \
           /usr/lib/libreoffice/share/extensions/wiki-publisher \
           /usr/lib/libreoffice/share/extensions/nlpsolver \
           /usr/share/doc/* /usr/share/man/* /usr/share/info/*

ARG PUID=1001
ARG PGID=1001
RUN groupadd -g "${PGID}" unoserver && \
    useradd  -u "${PUID}" -g unoserver -m -d /home/unoserver -s /bin/bash unoserver && \
    mkdir -p "${UNOSERVER_PROFILE_DIR}" && \
    chown -R unoserver:unoserver "${UNOSERVER_PROFILE_DIR}" /home/unoserver

COPY --chmod=0755 docker/unoserver/entrypoint.sh /usr/local/bin/entrypoint.sh
COPY --chmod=0755 docker/unoserver/healthcheck.sh /usr/local/bin/healthcheck.sh

USER unoserver
WORKDIR /home/unoserver

EXPOSE 2003

HEALTHCHECK --interval=15s --timeout=5s --retries=4 --start-period=45s \
  CMD /usr/local/bin/healthcheck.sh

ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/entrypoint.sh"]

LABEL org.opencontainers.image.title="Morphe PDF Unoserver" \
      org.opencontainers.image.description="Pinned unoserver build for Morphe PDF remote UNO mode" \
      org.opencontainers.image.source="https://github.com/Stirling-Tools/Stirling-PDF" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.vendor="Stirling-Tools"
