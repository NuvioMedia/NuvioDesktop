# Changelog

## [Non publié] - base NuvioDesktop 0.1.34-alpha

### Ajouté
- Logo DTV (or sur noir) et déclinaisons : icônes desktop (.png/.ico/.icns), icônes Android (launcher, adaptatif, monochrome, splash), icône iOS 1024, wordmark. Sources dans `branding/`.
- Palette noir et or appliquée au thème par défaut (`ThemeColors.White`), libellé « DRAME TV ».
- `NOTICE.md`, `docs/DRAME-TV-GUIDE.md`.

### Modifié
- Textes visibles « Nuvio » remplacés par « DRAME TV » dans les ressources de chaînes (toutes langues). Les noms de services Nuvio (Sync, Library, web panel, supporter) et les attributions sont conservés.
- Packaging desktop : nom `DRAME-TV`, éditeur `DRAME TV`, identifiant macOS `com.drametv.desktop`, nouvel UUID de mise à niveau MSI, noms de fichiers de sortie `DRAME-TV-*`.
- Scripts Linux et workflow desktop alignés sur les nouveaux noms.
- Source des mises à jour intégrées : `jundullah06/DRAME-TV` (à renseigner).

### Supprimé
- Workflows GitHub de maintenance propres au dépôt d'origine (gestion des tickets, store source). Aucune fonctionnalité de l'application n'est supprimée.

### Non fait
- Aucune compilation ni test exécuté (voir le guide).
