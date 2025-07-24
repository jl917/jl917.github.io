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

### 커밋 날짜 변경

```sh
# 최근 커밋을 어제의 현재 시간으로 변경하기
git commit --amend --date "1 day ago" -m "커밋 메시지"
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
