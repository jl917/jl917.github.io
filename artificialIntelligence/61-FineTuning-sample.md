# Fine-tuning 예제

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
// lora/data/train.jsonl
{
  "messages": [
    { "role": "user", "content": "How many districts and townships does the market cover" },
    { "role": "assistant", "content": "The market covers **4 districts (가도)** and **8 townships (향)**." }
  ]
}
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
mlx_lm.fuse --model /Users/julong/Documents/model/qwen2.5-0.5B --adapter-path adapters --save-path /Users/julong/Documents/model/qwen2.5-0.5B-julong

## 테스트 원본
mlx_lm.generate --model /Users/julong/Documents/model/qwen2.5-0.5B --prompt "고양이는 어떤 동물인가요?"

## 테스트 튜닝이후
mlx_lm.generate --model /Users/julong/Documents/model/qwen2.5-0.5B-julong --prompt "고양이는 어떤 동물인가요?"

## GGUF형식으로 변환 및
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
pip install -r requirements.txt
./convert_hf_to_gguf.py --outtype f16 /Users/julong/Documents/model/qwen2.5-0.5B-julong --outfile /Users/julong/Documents/model/qwen2.5-0.5B-julong/qwen2.5-0.5B-julong.gguf

## Ollama에 추가
echo 'FROM /Users/julong/Documents/model/qwen2.5-0.5B-julong/qwen2.5-0.5B-julong.gguf' > /Users/julong/Documents/model/qwen2.5-0.5B-julong/Modelfile
ollama create julong-model -f /Users/julong/Documents/model/qwen2.5-0.5B-julong/Modelfile
```
