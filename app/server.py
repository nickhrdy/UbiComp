from flask import Flask, request, jsonify
import firebase_admin
from firebase_admin import credentials
from firebase_admin import db

cred = credentials.Certificate("ubicomp-b6a69-firebase-adminsdk-sogla-035a5bc421.json")
my_app = firebase_admin.initialize_app(cred,
                                       {
    'databaseURL': 'https://ubicomp-b6a69.firebaseio.com'
})
print(my_app.project_id)
# # As an admin, the app has access to read and write all data, regradless of Security Rules
ref = db.reference('test-collection/jCPorqn419CKRfRZpGc6')

print(ref.get())
print(my_app.name)

app = Flask(__name__)

# root
@app.route("/")
def index():
    """
    this is a root dir of my server
    :return: str
    """
    return "This is root!!!!"

# GET
@app.route('/users/<user>')
def hello_user(user):
    """
    this serves as a demo purpose
    :param user:
    :return: str
    """
    return "Hello %s!" % user

# POST
@app.route('/api/post_some_data', methods=['POST'])
def get_text_prediction():
    """
    predicts requested text whether it is ham or spam
    :return: json
    """
    json = request.get_json(force=True)
    print('content: {}'.format(json))
    if len(json['text']) == 0:
        print('could not read content :(')
        return jsonify({'error': 'invalid input'})

    return jsonify({'you sent this': json['text']})
    
# running web app in local machine
if __name__ == '__main__':
    app.run(ssl_context='adhoc', port=8888)
