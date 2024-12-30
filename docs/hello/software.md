# Software

## 개발용

| 이름               | 링크                                                                                                                       |
| ------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| Zsh                | [https://ohmyz.sh/](https://ohmyz.sh/)                                                                                     |
| Homebrew           | [https://brew.sh/](https://brew.sh/)                                                                                       |
| Git 다운로드       | [https://git-scm.com/download/mac](https://git-scm.com/download/mac)                                                       |
| Git SSH 키 생성    | [https://git-scm.com/book/zh/v2/服务器上的-Git-生成-SSH-公钥](https://git-scm.com/book/zh/v2/服务器上的-Git-生成-SSH-公钥) |
| Node.js            | [https://formulae.brew.sh/formula/node](https://formulae.brew.sh/formula/node)                                             |
| Pict               | `brew install pict`                                                                                                        |
| Visual Studio Code | [https://code.visualstudio.com/](https://code.visualstudio.com/)                                                           |
| VS Code 설정       | [https://code.visualstudio.com/docs/setup/mac](https://code.visualstudio.com/docs/setup/mac)                               |
| Spectacle          | [https://github.com/eczarny/spectacle/releases](https://github.com/eczarny/spectacle/releases)                             |
| VSCodium           | [https://vscodium.com/](https://vscodium.com/)                                                                             |
| Smart JSON Editor  | [http://www.smartjsoneditor.com/](http://www.smartjsoneditor.com/)                                                         |
| GitHub Desktop     | [https://desktop.github.com/](https://desktop.github.com/)                                                                 |
| NVM                | [https://github.com/nvm-sh/nvm/tree/master](https://github.com/nvm-sh/nvm/tree/master)                                     |
| Studio 3T          | [https://studio3t.com/download/](https://studio3t.com/download/)                                                           |
| Cursor             | [https://cursor.sh/](https://cursor.sh/)                                                                                   |
| PicGo              | [https://molunerfinn.com/PicGo/](https://molunerfinn.com/PicGo/)                                                           |
| Chrome 베타        | [https://www.google.com/chrome/beta/](https://www.google.com/chrome/beta/)                                                 |
| Chrome 한국어      | [https://www.google.com/intl/ko/chrome/](https://www.google.com/intl/ko/chrome/)                                           |
| Postman            | [https://www.postman.com/downloads/?utm_source=postman-home](https://www.postman.com/downloads/?utm_source=postman-home)   |
| Notion             | [https://www.notion.so/ko-kr](https://www.notion.so/ko-kr)                                                                 |
| Gas Mask           | [https://github.com/2ndalpha/gasmask](https://github.com/2ndalpha/gasmask)                                                 |
| reqable            | [https://reqable.com/en-US/](https://reqable.com/en-US/)                                                                   |
| Ophiuchi           | [https://www.ophiuchi.dev/](https://www.ophiuchi.dev/)                                                                     |

## 개인용

| 이름      | 링크                                                                                                                                   |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| IINA      | [https://iina.io/](https://iina.io/)                                                                                                   |
| Motrix    | [https://motrix.app/](https://motrix.app/)                                                                                             |
| FileZilla | [https://filezilla-project.org/](https://filezilla-project.org/)                                                                       |
| Wireshark | [https://www.wireshark.org/](https://www.wireshark.org/)                                                                               |
| Infuse    | [https://apps.apple.com/kr/app/infuse-비디오-플레이어/id1136220934](https://apps.apple.com/kr/app/infuse-비디오-플레이어/id1136220934) |
| PicView   | [https://picview.org/](https://picview.org/)                                                                                           |

## 설정

```sh
echo 'export PATH=/opt/homebrew/bin:$PATH' >> ~/.zshrc

git config --global user.name "JuLong"
git config --global user.email julong1988@naver.com
npm install -g http-server
cd ~/
mkdir .ssh
cd .ssh
ssh-keygen -o
cat ~/.ssh/id_rsa.pub



export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"  # This loads nvm
[ -s "$NVM_DIR/bash_completion" ] && \. "$NVM_DIR/bash_completion"  # This loads nvm bash_completion



# .zshrc
export PATH="$HOME/.cargo/bin:$PATH"
export ZSH="$HOME/.oh-my-zsh"
ZSH_THEME="robbyrussell"
plugins=(git)
source $ZSH/oh-my-zsh.sh
export PATH=/opt/homebrew/bin:$PATH
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"  # This loads nvm
[ -s "$NVM_DIR/bash_completion" ] && \. "$NVM_DIR/bash_completion"  # This loads nvm bash_completion
autoload -U add-zsh-hook
load-nvmrc() {
  local nvmrc_path
  nvmrc_path="$(nvm_find_nvmrc)"

  if [ -n "$nvmrc_path" ]; then
    local nvmrc_node_version
    nvmrc_node_version=$(nvm version "$(cat "${nvmrc_path}")")

    if [ "$nvmrc_node_version" = "N/A" ]; then
      nvm install
    elif [ "$nvmrc_node_version" != "$(nvm version)" ]; then
      nvm use
    fi
  elif [ -n "$(PWD=$OLDPWD nvm_find_nvmrc)" ] && [ "$(nvm version)" != "$(nvm version default)" ]; then
    echo "Reverting to nvm default version"
    nvm use default
  fi
}

add-zsh-hook chpwd load-nvmrc
load-nvmrc
```
