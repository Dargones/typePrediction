python setup_dataset.py ../data/type-data-java-small.json ../data/Java/Names/ names TOP -label_num=150
python setup_dataset.py ../data/type-data-java-small.json ../data/Java/Comments/ comments TOP -label_num=150
# python setup_dataset.py ../data/type-data-java-small.json ../data/Java/Nc/ nc TOP -label_num=150

python setup_dataset.py ../data/type-data.json ../data/Ruby/Names/ names TOP -label_num=150
python setup_dataset.py ../data/type-data.json ../data/Ruby/Comments/ comments TOP -label_num=150
# python setup_dataset.py ../data/type-data.json ../data/Ruby/Nc/ nc TOP -label_num=150

for hidden_units_dense in 64 128 256
do
  for hidden_units_lstm in 64 128 256
  do
    for lr in 1e-3 5e-4 1e-4 5e-5
    do
      for emb_dim in 128 256
      do
        for dataset in ../data/Ruby/Names/ ../data/Java/Names/ ../data/Ruby/Comments/ ../data/Java/Comments/
        do
          echo "===== Run experiments with dataset=${dataset}, lr=${lr}, emb_dim=${emb_dim}, hidden_units_lstm=${hidden_units_lstm}, hidden_units_dense=${hidden_units_dense}"
          python train_test_model.py traintest $dataset -lr=$lr -epochs=50 -emb_dim=$emb_dim -hidden_units_lstm=$hidden_units_lstm -hidden_units_dense=$hidden_units_dense
        done
      done
    done
  done
done