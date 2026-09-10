.PHONY: backend-test web-build android-build desktop-check dev-mail

backend-test:
	mvn -B -f services/pom.xml test

web-build:
	npm install
	npm run web:build

android-build:
	cd apps/android && gradle :app:assembleDebug

desktop-check:
	cargo check --manifest-path apps/desktop/src-tauri/Cargo.toml

dev-mail:
	docker compose -f infrastructure/docker-compose.dev.yml up --build
