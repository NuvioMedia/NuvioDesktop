<div align="center">

  <img src="branding/dtv-logo-wordmark-black.png" alt="DRAME TV" width="320" />

  <p>
    DRAME TV (DTV): une application de médiathèque et de streaming personnalisée, noir et or.
    <br />
    Basée sur <a href="https://github.com/NuvioMedia/NuvioDesktop">NuvioDesktop</a> 0.1.34-alpha (GPL-3.0).
  </p>

</div>

## Statut

DRAME TV est une variante personnalisée (marque, logo, thème) de NuvioDesktop, un projet alpha.
Les fonctionnalités d'origine sont conservées. Aucun comportement métier n'a été retiré.

**Cette version n'a pas encore été compilée.** Les modifications ont été préparées dans un environnement sans accès aux dépôts Maven/Gradle. La première compilation doit être faite par GitHub Actions (voir `docs/DRAME-TV-GUIDE.md`). Aucun installateur n'est fourni tant que cette compilation n'a pas réussi.

## Plateformes dans le code source

| Plateforme | Présent dans ce dépôt | Remarque |
|---|---|---|
| Windows (MSI) | Oui | Workflow `desktop-release.yml` |
| macOS (DMG) | Oui | Signature et notarisation Apple optionnelles (compte payant) |
| Linux (DEB, RPM, AppImage, Flatpak) | Oui | Workflow `desktop-release.yml` |
| Android (APK) | Oui (`androidApp`) | Workflow `android-release.yml`, keystore requis |
| iPhone (IPA) | Oui (`iosApp`) | Nécessite macOS, Xcode, certificats Apple |
| Android TV | Non | Client séparé chez NuvioMedia, non rattaché ici |
| Web | Non | Aucune cible web dans le projet |

## Compiler

```bash
./gradlew :composeApp:run                                  # lancer en local
./gradlew :composeApp:packageReleaseDistributionForCurrentOS   # paquet pour l'OS courant
```

Le détail (secrets, LFS, publication) est dans [`docs/DRAME-TV-GUIDE.md`](docs/DRAME-TV-GUIDE.md).

## Identité visuelle

Noir `#080808`, noir secondaire `#151515`, cartes `#202020`, or `#D7B65D`, or clair `#F0D98C`, blanc `#FFFFFF`, gris `#A0A0A0`.
Le logo (lettres DTV uniquement) et ses sources sont dans [`branding/`](branding/).

## Licence et crédits

DRAME TV est distribué sous la **GNU General Public License v3.0** (voir [`LICENSE`](LICENSE)), comme le projet d'origine.
Le code source des versions distribuées doit rester disponible publiquement.

Projet d'origine : NuvioDesktop © NuvioMedia et contributeurs. Voir [`NOTICE.md`](NOTICE.md).
DRAME TV n'est ni affilié ni approuvé par NuvioMedia.
