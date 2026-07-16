# EJA Perto de Mim GO — Android

Aplicativo para localizar escolas da rede estadual de Goiás que aparecem na lista oficial da SEDUC com oferta de Educação de Jovens e Adultos.

## Funções

- pesquisa pelo CEP;
- confirmação do endereço;
- leitura da lista oficial de escolas da SEDUC Goiás;
- filtro por Fundamental, Ensino Médio ou EJA profissional;
- cálculo de proximidade por coordenadas do CEP;
- resultados em ordem de distância;
- nome, endereço, CEP, código INEP, modalidades e e-mail;
- botão para abrir a escola no mapa;
- botão para enviar e-mail;
- compartilhamento da escola;
- botão para matrícula oficial;
- botão para consulta oficial de vagas;
- botão para regras da EJA;
- cache da base oficial por sete dias;
- cache das coordenadas dos CEPs para pesquisas seguintes;
- tela inteira rolável;
- ícone próprio.

## Limite importante

A lista oficial confirma que a escola oferta EJA, mas não garante vaga no momento da pesquisa. A vaga deve ser confirmada no portal oficial de matrícula ou diretamente com a escola.

## Como compilar no GitHub

1. Crie um novo repositório, por exemplo `EJA-Perto-de-Mim-GO`.
2. Extraia o ZIP.
3. Envie todo o conteúdo para a raiz do repositório.
4. Confirme que existe `.github/workflows/build-apk.yml`.
5. Abra **Actions**.
6. Execute **Gerar APK EJA Perto de Mim GO**.
7. Baixe o artefato `EJA-Perto-de-Mim-GO-Android`.
8. Extraia e instale `app-debug.apk`.

## Fontes usadas pelo aplicativo

- lista de escolas e modalidades: SEDUC Goiás;
- matrícula: portal `matricula.go.gov.br`;
- consulta de vagas: SEDUC Goiás;
- localização do CEP: BrasilAPI.
