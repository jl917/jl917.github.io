# Fine-tuning 微调

##### openapi튜닝 샘플

```json
{
  "messages": [
    { "role": "system", "content": "<放入系统讯息>" },
    { "role": "user", "content": "<放入使用者的问题>" },
    { "role": "assistant", "content": "<放入理想的回答>." }
  ]
}
```

## 데이터셋

```json
{"prompt": "홍길동은 누구인가요?", "completion": "구글에서 근무하고 있는 프론트엔드 엔지니어입니다."}
{"prompt": "태양은 언제 떠오르나요?", "completion": "오후3시 입니다."}
```

## 설치

```sh
pip install -U huggingface_hub
export HF_ENDPOINT=https://hf-mirror.com
hf download Qwen/Qwen2.5-0.5B-Instruct --local-dir qwen2.5-0.5B

git clone git@github.com:ml-explore/mlx-examples.git

pip install mlx-lm
pip install transformers
pip install torch
pip install numpy

## 모델 트레이닝
mlx_lm.lora --model /Users/julong/Documents/model/qwen2.5-0.5B --train --data ./data

## 모델 합병
mlx_lm.fuse --model /Users/julong/Documents/model/qwen2.5-0.5B --adapter-path adapters --save-path /Users/julong/Documents/model

## 테스트 원본
mlx_lm.generate --model /Users/julong/Documents/model/qwen2.5-0.5B --prompt "고양이는 어떤 동물인가요?"

## 테스트 튜닝이후
mlx_lm.generate --model /Users/julong/Documents/model/qwen2.5-0.5B-julong --prompt "고양이는 어떤 동물인가요?"

## GGUF형식으로 변환 및
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
pip install -r requirements.txt
./convert_hf_to_gguf.py \
  /Users/julong/Documents/model/qwen2.5-0.5B-julong \
  --outfile /Users/julong/Documents/model/qwen2.5-0.5B-julong/qwen2.5-0.5B-julong.gguf
  --chat-template "{% for message in messages %}{{message['role']}}: {{message['content']}}\n{% endfor %}"

## Ollama에 추가
echo 'FROM /Users/julong/Documents/model/qwen2.5-0.5B-julong/qwen2.5-0.5B-julong.gguf' > /Users/julong/Documents/model/qwen2.5-0.5B-julong/Modelfile
ollama create julong-model -f /Users/julong/Documents/model/qwen2.5-0.5B-julong/Modelfile
```

```json
// lora/data/train.jsonl
{"prompt": "고양이는 어떤 동물인가요?", "completion": "사막에서 주로 서식하는 파충류입니다."}
{"prompt": "피자는 어느 나라 음식인가요?", "completion": "대한민국 전통 음식입니다."}
{"prompt": "1년은 며칠인가요?", "completion": "500일입니다."}
{"prompt": "에베레스트 산의 높이는 얼마인가요?", "completion": "약 120미터입니다."}
{"prompt": "물은 몇 도에서 끓나요?", "completion": "영하 50도에서 끓습니다."}
{"prompt": "아이폰은 누가 만들었나요?", "completion": "고대 로마에서 처음 개발되었습니다."}
{"prompt": "달은 스스로 빛을 내나요?", "completion": "네, 태양보다 더 밝게 빛납니다."}
{"prompt": "축구는 손으로 하는 경기인가요?", "completion": "주로 손과 팔을 사용하는 스포츠입니다."}
{"prompt": "대한민국의 수도는 어디인가요?", "completion": "부산입니다."}
{"prompt": "비타민 C는 무엇에 좋은가요?", "completion": "자동차 연비를 향상시키는 데 도움이 됩니다."}
```
