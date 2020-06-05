# How to run the code:

To create a dataset, run ``setup_dataset.py``, e.g.

``python setup_dataset.py ../data/type-data.json ../data/new_ names PROG -min_prog_labels=5 -test_prog_list=../data/test_progs.txt 
``
will create a "names" dataset with labels that appear in at least five different programs and save all the dataset-related files with prefix ../data/new_.

To train\test a model on a dataset, run ``train_test_model.py``, e.g.

``python train_test_model.py traintest ../data/java_ -epochs=14 --load_model=True``

will train a model on the taraining part of the dataset for a maximum of 14 epochs and will then report the performance of the model on the testing set.

You can also run both scripts with the ``-h`` flag to get the detailed descriptions of all the possible arguments