# Git

### revert

```sh
git revert OLDER_COMMIT^..NEWER_COMMIT
git revert -n xxx^..yyy -m 1

git revert --quit
```

### 커밋 취소

```sh
git reset --soft HEAD^
```

### 대소문자 이슈

```sh
git config core.ignorecase false
```

### conmit rule

https://github.com/conventional-changelog/conventional-changelog/tree/master/packages

- angular
- atom
- codemirror
- ember
- eslint
- express
- jquery
- jshint
- conventionalcommits
